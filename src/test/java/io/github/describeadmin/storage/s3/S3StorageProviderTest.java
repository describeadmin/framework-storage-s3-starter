package io.github.describeadmin.storage.s3;

import io.github.describeadmin.storage.api.StorageObject;
import io.github.describeadmin.storage.s3.core.S3StorageProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link S3StorageProvider} 针对真实 S3 兼容服务的行为测试。
 *
 * <p>用例与 framework 仓 {@code LocalFileStorageProviderTest} 一一对应（准入规范第 9 条）：
 * 两种实现遵守同一份 {@code StorageProvider} 语义，行为差异如果存在，应当出现在这里，
 * 而不是出现在业务方切换后端的那一刻。
 */
@DisplayName("S3 文件存储")
class S3StorageProviderTest extends AbstractS3Test {

    private static final S3Client CLIENT = newClient();
    private static final S3Presigner PRESIGNER = newPresigner();

    @AfterAll
    static void closeClients() {
        CLIENT.close();
        PRESIGNER.close();
    }

    private S3StorageProvider newProvider() {
        return new S3StorageProvider(CLIENT, PRESIGNER, BUCKET, "", ENDPOINT + "/" + BUCKET);
    }

    private static InputStream content(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void cleanUp() {
        cleanBucket();
    }

    @Nested
    @DisplayName("写入与读取")
    class ReadWrite {

        private final S3StorageProvider storage = newProvider();

        @Test
        @DisplayName("写入后可读出相同内容")
        void putThenGetReturnsSameBytes() throws Exception {
            storage.put("a/b.txt", content("中文内容"), 0, "text/plain");

            try (InputStream in = storage.get("a/b.txt").orElseThrow()) {
                // 值断言而非存在性断言：编码坏掉时对象照样"存在"（CLAUDE.md 3.6）
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("中文内容");
            }
        }

        @Test
        @DisplayName("传入了准确 size 时走流式上传，内容与返回的 size 都正确")
        void putWithKnownSizeStreamsCorrectly() throws Exception {
            byte[] bytes = "streamed-中文".getBytes(StandardCharsets.UTF_8);
            StorageObject object = storage.put("sized.txt", new ByteArrayInputStream(bytes),
                    bytes.length, "text/plain");

            assertThat(object.size()).isEqualTo((long) bytes.length);
            try (InputStream in = storage.get("sized.txt").orElseThrow()) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("streamed-中文");
            }
        }

        @Test
        @DisplayName("size<=0（大小未知）时缓冲上传，StorageObject.size 反映实际字节数")
        void putBuffersAndReportsRealSizeWhenSizeUnknown() {
            StorageObject object = storage.put("k", content("12345"), 0, "text/plain");

            assertThat(object.size()).isEqualTo(5L);
        }

        @Test
        @DisplayName("⚠️ 与本地实现的行为差异：传入的 size>0 时被当作真实 Content-Length，"
                + "对不上是调用方错误、直接失败，而不像本地实现那样静默按落盘字节数纠正")
        void inaccuratePositiveSizeIsCallerError() {
            // 本地实现 put("k", 5 字节, 999) 会忽略 999、按 5 返回；S3 的流式上传必须提前知道
            // 准确的 Content-Length，给错了只能失败。SPI 明确 size "实现不要求必须以此为准"，
            // 但没承诺能纠正错误值——需要"未知大小"语义时应传 size<=0。
            assertThatThrownBy(() -> storage.put("bad", content("12345"), 999, "text/plain"))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("contentType 被对象存储持久化，可通过 HEAD 读回")
        void contentTypeIsPersisted() {
            storage.put("typed.json", content("{}"), 0, "application/json");

            HeadObjectResponse head = CLIENT.headObject(b -> b.bucket(BUCKET).key("typed.json"));
            assertThat(head.contentType()).isEqualTo("application/json");
        }

        @Test
        @DisplayName("url 使用配置的基地址拼接 key")
        void urlUsesConfiguredBase() {
            assertThat(storage.url("a/b.txt")).isEqualTo(ENDPOINT + "/" + BUCKET + "/a/b.txt");
        }

        @Test
        @DisplayName("presignedUrl 返回的地址能被 HTTP 真正下载到")
        void presignedUrlIsActuallyFetchable() throws Exception {
            storage.put("preview/pic.txt", content("签名可下载"), 0, "text/plain");

            String url = storage.presignedUrl("preview/pic.txt", Duration.ofMinutes(5));
            assertThat(url).contains("X-Amz-Signature").contains("preview/pic.txt");

            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("签名可下载");
        }

        @Test
        @DisplayName("未写入的 key 读取返回空，而不是异常")
        void getMissingKeyReturnsEmpty() {
            assertThat(storage.get("never-written")).isEmpty();
        }

        @Test
        @DisplayName("exists 随 put/remove 变化")
        void existsReflectsPutAndRemove() {
            assertThat(storage.exists("k")).isFalse();

            storage.put("k", content("v"), 0, null);
            assertThat(storage.exists("k")).isTrue();

            storage.remove("k");
            assertThat(storage.exists("k")).isFalse();
        }

        @Test
        @DisplayName("删除不存在的 key 静默返回（S3 DeleteObject 幂等）")
        void removeMissingKeyIsNoop() {
            storage.remove("never-written");
        }
    }

    @Nested
    @DisplayName("覆盖语义")
    class Overwrite {

        @Test
        @DisplayName("同 key 重复写入替换旧内容")
        void putOverwritesExistingKey() throws Exception {
            S3StorageProvider storage = newProvider();

            storage.put("k", content("old"), 0, null);
            storage.put("k", content("new"), 0, null);

            try (InputStream in = storage.get("k").orElseThrow()) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("new");
            }
        }
    }

    @Nested
    @DisplayName("键前缀")
    class KeyPrefix {

        @Test
        @DisplayName("配置了 keyPrefix 后，真实对象键带前缀，而回调方仍用不带前缀的 key")
        void prefixIsAppliedToRealObjectKey() throws Exception {
            S3StorageProvider storage = new S3StorageProvider(
                    CLIENT, PRESIGNER, BUCKET, "avatars/", ENDPOINT + "/" + BUCKET);

            StorageObject object = storage.put("1.png", content("头像"), 0, "image/png");

            // 调用方视角：key 不带前缀
            assertThat(object.key()).isEqualTo("1.png");
            assertThat(storage.exists("1.png")).isTrue();
            // 存储视角：真实对象键带前缀
            assertThat(CLIENT.headObject(b -> b.bucket(BUCKET).key("avatars/1.png")).contentLength())
                    .isEqualTo(6L);
            assertThat(storage.url("1.png")).endsWith("/avatars/1.png");
        }
    }

    @Nested
    @DisplayName("非法 key 处理")
    class KeyValidation {

        private final S3StorageProvider storage = newProvider();

        static Stream<String> maliciousKeys() {
            return Stream.of("../secret.txt", "a/../../secret.txt", "/etc/passwd",
                    "a\\..\\secret.txt", "C:/windows/win.ini");
        }

        @Test
        @DisplayName("写方法拒绝含 .. 片段的 key")
        void rejectsKeyWithDotDotSegment() {
            assertThatThrownBy(() -> storage.put("../secret.txt", content("x"), 0, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("写方法拒绝以 / 开头的 key")
        void rejectsKeyWithLeadingSlash() {
            assertThatThrownBy(() -> storage.put("/etc/passwd", content("x"), 0, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("写方法拒绝含反斜杠的 key")
        void rejectsKeyWithBackslash() {
            assertThatThrownBy(() -> storage.put("a\\..\\secret.txt", content("x"), 0, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("写方法拒绝带盘符的 key")
        void rejectsWindowsDriveLetterKey() {
            assertThatThrownBy(() -> storage.put("C:/windows/win.ini", content("x"), 0, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("写方法拒绝空白或 null 的 key")
        void rejectsBlankOrNullKeyOnWrite() {
            assertThatThrownBy(() -> storage.put("", content("x"), 0, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.put(null, content("x"), 0, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.remove(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("presignedUrl 对非正的有效期抛 IllegalArgumentException")
        void presignedUrlRejectsNonPositiveExpiry() {
            assertThatThrownBy(() -> storage.presignedUrl("k", Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.presignedUrl("k", Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("读方法对同一批非法 key 按未命中处理，不抛异常")
        void readMethodsTreatInvalidKeyAsMiss() {
            maliciousKeys().forEach(key -> {
                assertThat(storage.get(key)).as("get(%s)", key).isEmpty();
                assertThat(storage.exists(key)).as("exists(%s)", key).isFalse();
            });
            assertThat(storage.get(null)).isEmpty();
            assertThat(storage.exists(null)).isFalse();
        }
    }
}
