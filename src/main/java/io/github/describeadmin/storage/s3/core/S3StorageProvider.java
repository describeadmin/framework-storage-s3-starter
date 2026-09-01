package io.github.describeadmin.storage.s3.core;

import io.github.describeadmin.storage.api.StorageException;
import io.github.describeadmin.storage.api.StorageObject;
import io.github.describeadmin.storage.api.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 以 S3 / S3 兼容对象存储为后端的 {@link StorageProvider} 实现。
 *
 * <p>与 {@code LocalFileStorageProvider} 遵守同一份 SPI 语义（见 {@link StorageProvider} 类注释）：
 * <ul>
 *   <li>{@link #get} / {@link #exists} 对不存在或不合法的 key 返回空值 / {@code false}，不抛异常</li>
 *   <li>{@link #put} / {@link #remove} 对不合法的 key 抛 {@link IllegalArgumentException}</li>
 *   <li>{@link #put} 为同 key 覆盖 —— 这正是 S3 {@code PutObject} 的天然语义，无需额外处理</li>
 * </ul>
 *
 * <p><b>key 的格式校验保留</b>：对象存储的命名空间是扁平的，{@code ..} 之类不构成路径穿越风险，
 * 但两种实现行为一致能让业务方在切换后端时不踩坑（同一个 key 在本地能写、到了 S3 也能写），
 * 因此这里沿用与本地实现相同的一套拒绝规则。
 *
 * <p><b>{@link #put} 对未知大小内容的处理</b>：S3 的同步客户端上传时必须提前知道
 * {@code Content-Length}。调用方传入的 {@code size > 0} 时直接流式上传；{@code size <= 0} 时
 * 只能先把整个流读进内存再上传。<b>上传大文件时务必传准确的 size</b>，否则堆内存会被吃满。
 */
public class S3StorageProvider implements StorageProvider {

    private static final Logger log = LoggerFactory.getLogger(S3StorageProvider.class);

    private static final Pattern WINDOWS_DRIVE_LETTER = Pattern.compile("^[A-Za-z]:.*");

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    /** 规整后的键前缀：要么是空串，要么是以 {@code /} 结尾、不以 {@code /} 开头的一段。 */
    private final String keyPrefix;
    /** {@link #url} 的基地址，已包含桶信息、不带尾部 {@code /}。 */
    private final String urlBase;

    public S3StorageProvider(S3Client client, S3Presigner presigner,
                             String bucket, String keyPrefix, String urlBase) {
        if (client == null || presigner == null) {
            throw new IllegalArgumentException("S3Client 与 S3Presigner 不能为空");
        }
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("桶名不能为空，请配置 describeadmin.storage.s3.bucket");
        }
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
        this.keyPrefix = normalizePrefix(keyPrefix);
        this.urlBase = urlBase == null || urlBase.isBlank()
                ? "" : (urlBase.endsWith("/") ? urlBase.substring(0, urlBase.length() - 1) : urlBase);
    }

    @Override
    public StorageObject put(String key, InputStream content, long size, String contentType) {
        if (content == null) {
            throw new IllegalArgumentException("写入内容不能为 null");
        }
        validateKeyFormat(key);
        String objectKey = fullKey(key);

        RequestBody body;
        long contentLength;
        if (size > 0) {
            body = RequestBody.fromInputStream(content, size);
            contentLength = size;
        } else {
            byte[] buffered;
            try {
                buffered = content.readAllBytes();
            } catch (IOException e) {
                throw new StorageException("读取待上传内容失败: " + key, e);
            }
            body = RequestBody.fromBytes(buffered);
            contentLength = buffered.length;
        }

        PutObjectRequest.Builder request = PutObjectRequest.builder().bucket(bucket).key(objectKey);
        if (contentType != null && !contentType.isBlank()) {
            request.contentType(contentType);
        }
        try {
            client.putObject(request.build(), body);
        } catch (SdkException e) {
            throw new StorageException("上传失败: " + key, e);
        }
        return new StorageObject(key, url(key), contentLength, contentType);
    }

    @Override
    public Optional<InputStream> get(String key) {
        if (isInvalidForRead(key)) {
            return Optional.empty();
        }
        try {
            ResponseInputStream<GetObjectResponse> stream = client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(fullKey(key)).build());
            return Optional.of(stream);
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            // key 合法、对象也确实该在，读取仍失败说明是真正的 I/O / 权限故障，
            // 与"未命中"是两回事，要抛出去
            throw new StorageException("下载失败: " + key, e);
        } catch (SdkException e) {
            throw new StorageException("下载失败: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        if (isInvalidForRead(key)) {
            return false;
        }
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(fullKey(key)).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new StorageException("查询对象是否存在失败: " + key, e);
        } catch (SdkException e) {
            throw new StorageException("查询对象是否存在失败: " + key, e);
        }
    }

    @Override
    public void remove(String key) {
        validateKeyFormat(key);
        try {
            // S3 的 DeleteObject 是幂等的：对象不存在也返回成功，天然满足 SPI 的"静默返回"
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(fullKey(key)).build());
        } catch (SdkException e) {
            throw new StorageException("删除失败: " + key, e);
        }
    }

    @Override
    public String url(String key) {
        if (key == null) {
            return null;
        }
        String path = fullKey(key);
        return urlBase.isEmpty() ? path : urlBase + "/" + path;
    }

    /**
     * 返回一个限时有效的预签名下载地址。
     *
     * <p>S3 兼容存储的真实部署几乎总是私有桶，{@link #url} 拼出来的地址通常无法直接访问，
     * 业务方的下载 / 预览链接应当用本方法取。
     *
     * @param key    对象键
     * @param expiry 有效期，必须为正数
     * @throws IllegalArgumentException key 不合法，或 {@code expiry} 非正
     */
    @Override
    public String presignedUrl(String key, Duration expiry) {
        if (expiry == null || expiry.isZero() || expiry.isNegative()) {
            throw new IllegalArgumentException("预签名有效期必须为正数: " + expiry);
        }
        validateKeyFormat(key);
        GetObjectRequest getRequest = GetObjectRequest.builder().bucket(bucket).key(fullKey(key)).build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .getObjectRequest(getRequest)
                .build();
        return presigner.presignGetObject(presignRequest).url().toString();
    }

    /**
     * 桶不存在则创建。仅在 {@code describeadmin.storage.s3.auto-create-bucket=true} 时由自动配置调用。
     *
     * <p>这是本类<b>唯一</b>会在应用启动阶段触碰网络的路径——没开这个开关时，
     * 首次真正读写对象之前不会与对象存储发生任何通信（与 cache-redis 的惰性连接一致）。
     */
    public void createBucketIfMissing() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return;
        } catch (NoSuchBucketException e) {
            // 落到下面去建桶
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw new StorageException("检查桶是否存在失败: " + bucket, e);
            }
        } catch (SdkException e) {
            throw new StorageException("检查桶是否存在失败: " + bucket, e);
        }
        try {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("自动创建对象存储桶: {}", bucket);
        } catch (S3Exception e) {
            // 并发启动的另一个实例可能刚好抢先建好了，这不算错
            if (e.statusCode() == 409 || "BucketAlreadyOwnedByYou".equals(e.awsErrorDetails().errorCode())
                    || "BucketAlreadyExists".equals(e.awsErrorDetails().errorCode())) {
                return;
            }
            throw new StorageException("创建桶失败: " + bucket, e);
        } catch (SdkException e) {
            throw new StorageException("创建桶失败: " + bucket, e);
        }
    }

    private String fullKey(String key) {
        return keyPrefix + key;
    }

    /** 读方法用：key 不合法一律按未命中处理，不抛异常。 */
    private boolean isInvalidForRead(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
        try {
            validateKeyFormat(key);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /**
     * key 格式校验，与 {@code LocalFileStorageProvider} 保持一致的一套拒绝规则。
     * 写方法直接调用，不合法即抛 {@link IllegalArgumentException}。
     */
    private static void validateKeyFormat(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("对象键不能为空");
        }
        if (key.startsWith("/") || key.startsWith("\\")) {
            throw new IllegalArgumentException("对象键不能以路径分隔符开头: " + key);
        }
        if (key.contains("\\")) {
            throw new IllegalArgumentException("对象键不能包含反斜杠: " + key);
        }
        if (WINDOWS_DRIVE_LETTER.matcher(key).matches()) {
            throw new IllegalArgumentException("对象键不能包含盘符: " + key);
        }
        for (String segment : key.split("/")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("对象键不能包含 .. 片段: " + key);
            }
        }
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String trimmed = prefix.strip();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "" : trimmed + "/";
    }
}
