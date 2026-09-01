package io.github.describeadmin.storage.s3;

import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.util.UUID;

/**
 * 起一个真实的 S3 兼容服务供本模块测试使用。
 *
 * <p><b>为什么不用 mock</b>：本模块的全部价值就在于"与真实 S3 语义一致"——预签名 URL 能否被
 * HTTP 真正拉到、path-style 寻址、{@code NoSuchKey} 的错误码、{@code DeleteObject} 的幂等性，
 * 这些恰恰是 mock 掉之后就再也验证不到的东西。
 *
 * <p><b>默认后端是 Testcontainers 起的 MinIO</b>（S3 兼容、CI 可复现）。也可以指向一个已在运行的
 * 外部 S3 兼容服务，跳过容器：
 * <pre>
 *   mvn test -Ds3.endpoint=http://localhost:9000 -Ds3.access-key=rustfsadmin -Ds3.secret-key=rustfsadmin
 * </pre>
 * 这正是"用 RustFS 进行测试"的入口——RustFS 与 MinIO 一样是 S3 协议实现，同一套用例都应通过。
 *
 * <p>整个 JVM 共用一个容器 / 一个桶：容器启动是这组测试里最慢的一步。用例之间用
 * {@link #cleanBucket()} 清空对象，避免"单独跑能过、一起跑就挂"。
 */
public abstract class AbstractS3Test {

    private static final String EXTERNAL_ENDPOINT = System.getProperty("s3.endpoint", "").trim();
    private static final String IMAGE =
            System.getProperty("minio.image", "minio/minio:RELEASE.2024-06-13T22-53-53Z");

    private static final MinIOContainer MINIO;

    protected static final String REGION = "us-east-1";
    protected static final String ENDPOINT;
    protected static final String ACCESS_KEY;
    protected static final String SECRET_KEY;
    protected static final boolean PATH_STYLE = true;
    protected static final String BUCKET =
            "describeadmin-s3-test-" + UUID.randomUUID().toString().substring(0, 8);

    static {
        if (EXTERNAL_ENDPOINT.isEmpty()) {
            MINIO = new MinIOContainer(DockerImageName.parse(IMAGE));
            MINIO.start();
            ENDPOINT = MINIO.getS3URL();
            ACCESS_KEY = MINIO.getUserName();
            SECRET_KEY = MINIO.getPassword();
        } else {
            MINIO = null;
            ENDPOINT = EXTERNAL_ENDPOINT;
            ACCESS_KEY = System.getProperty("s3.access-key", "rustfsadmin");
            SECRET_KEY = System.getProperty("s3.secret-key", "rustfsadmin");
        }
        try (S3Client bootstrap = newClient()) {
            bootstrap.createBucket(b -> b.bucket(BUCKET));
        }
    }

    protected static S3Client newClient() {
        return S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(REGION))
                .endpointOverride(URI.create(ENDPOINT))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(PATH_STYLE).build())
                .build();
    }

    protected static S3Presigner newPresigner() {
        return S3Presigner.builder()
                .region(Region.of(REGION))
                .endpointOverride(URI.create(ENDPOINT))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(PATH_STYLE).build())
                .build();
    }

    /** 上传一个对象，绕过被测代码，用于给读路径的用例铺数据。 */
    protected static void putRaw(S3Client client, String key, byte[] bytes, String contentType) {
        client.putObject(b -> b.bucket(BUCKET).key(key).contentType(contentType),
                RequestBody.fromBytes(bytes));
    }

    /**
     * 清空桶里的全部对象。每个用例前调用，共用桶意味着用例之间会互相看到对方写的对象。
     *
     * <p>逐个 {@code deleteObject} 而不是批量 {@code deleteObjects}：批量删除属于
     * "SDK 必须附带完整性校验头"的操作，较老的 S3 兼容实现（这里用的 MinIO 镜像）
     * 只认旧的 {@code Content-Md5}、不认新 SDK 默认的 {@code x-amz-checksum-crc32}，
     * 会以 400 拒绝。单对象删除没有这个要求。被测代码 {@code S3StorageProvider.remove}
     * 用的也是单对象删除，所以这不是被测行为的问题，只是测试夹具要绕开。
     */
    protected static void cleanBucket() {
        try (S3Client client = newClient()) {
            ListObjectsV2Response listed = client.listObjectsV2(b -> b.bucket(BUCKET));
            listed.contents().forEach(o -> client.deleteObject(b -> b.bucket(BUCKET).key(o.key())));
        }
    }
}
