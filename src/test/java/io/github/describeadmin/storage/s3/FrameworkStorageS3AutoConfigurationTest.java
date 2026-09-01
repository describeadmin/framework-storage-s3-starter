package io.github.describeadmin.storage.s3;

import io.github.describeadmin.common.api.FrameworkVersion;
import io.github.describeadmin.storage.api.StorageObject;
import io.github.describeadmin.storage.api.StorageProvider;
import io.github.describeadmin.storage.autoconfigure.FrameworkStorageAutoConfiguration;
import io.github.describeadmin.storage.core.LocalFileStorageProvider;
import io.github.describeadmin.storage.s3.autoconfigure.FrameworkStorageS3AutoConfiguration;
import io.github.describeadmin.storage.s3.core.S3StorageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 插件装配机制的验证——本模块最重要的一组测试。
 *
 * <p>它回答的不是"S3 实现写得对不对"（那由 {@link S3StorageProviderTest} 负责），而是
 * <b>"把这个 jar 放进 classpath，它到底有没有接管 {@code StorageProvider}"</b>。
 *
 * <p>这个问题值得单独测，因为失败方式极其隐蔽：核心用
 * {@code @ConditionalOnMissingBean(StorageProvider.class)} 提供本地磁盘兜底，
 * 该条件只看当前已注册的 Bean 定义。插件若晚于核心被评估，插件的 Bean 会被自己的条件挡掉——
 * <b>启动完全正常、日志毫无异常</b>，直到发现上传的文件全落在了本地磁盘而不是对象存储。
 *
 * <p>{@link AutoConfigurations} 会按 {@code @AutoConfiguration} 的 before/after 真实排序，
 * 因此本测试验证的确实是那些注解，而不只是 Bean 方法本身。
 */
@DisplayName("插件装配")
class FrameworkStorageS3AutoConfigurationTest extends AbstractS3Test {

    private static final String LOCAL_ROOT =
            System.getProperty("java.io.tmpdir") + "/describeadmin-s3-fallback-" + UUID.randomUUID();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    FrameworkStorageS3AutoConfiguration.class,
                    FrameworkStorageAutoConfiguration.class))
            .withPropertyValues(
                    "describeadmin.storage.s3.endpoint=" + ENDPOINT,
                    "describeadmin.storage.s3.access-key=" + ACCESS_KEY,
                    "describeadmin.storage.s3.secret-key=" + SECRET_KEY,
                    "describeadmin.storage.s3.bucket=" + BUCKET,
                    "describeadmin.storage.s3.region=" + REGION,
                    "describeadmin.storage.s3.path-style-access=true",
                    "describeadmin.storage.local.root-dir=" + LOCAL_ROOT);

    @Test
    @DisplayName("插件声明的最低框架版本，与它实际构建所依赖的框架是自洽的")
    void declaredRequirementIsSatisfiedByTheFrameworkItBuildsAgainst() {
        assertThat(FrameworkVersion.current()).isNotEqualTo(FrameworkVersion.UNKNOWN);
        assertThatNoException().isThrownBy(() -> FrameworkVersion.requireCompatible(
                "framework-storage-s3-starter",
                FrameworkStorageS3AutoConfiguration.REQUIRED_FRAMEWORK_VERSION));
    }

    @Test
    @DisplayName("框架比插件要求的旧时，启动即失败而不是运行期 NoClassDefFoundError")
    void incompatibleFrameworkFailsFast() {
        assertThatThrownBy(() -> FrameworkVersion.requireCompatible(
                "framework-storage-s3-starter", "99.0.0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("framework-storage-s3-starter");
    }

    @Test
    @DisplayName("插件在 classpath 上时接管 StorageProvider，核心的本地磁盘实现让位")
    void pluginTakesOverStorageProvider() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(StorageProvider.class);
            assertThat(context.getBean(StorageProvider.class))
                    .as("装配顺序不对时这里会是 LocalFileStorageProvider，且不会有任何报错")
                    .isInstanceOf(S3StorageProvider.class);
        });
    }

    @Test
    @DisplayName("接管后的 StorageProvider 端到端可用：写入的对象确实落在对象存储上")
    void takenOverProviderActuallyWritesToObjectStore() {
        runner.run(context -> {
            cleanBucket();
            StorageProvider storage = context.getBean(StorageProvider.class);
            StorageObject object = storage.put("smoke/hello.txt",
                    new java.io.ByteArrayInputStream("装配冒烟".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    0, "text/plain");
            assertThat(object.key()).isEqualTo("smoke/hello.txt");

            try (S3Client raw = newClient()) {
                assertThat(raw.headObject(b -> b.bucket(BUCKET).key("smoke/hello.txt")).contentLength())
                        .isEqualTo("装配冒烟".getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            }
        });
    }

    @Test
    @DisplayName("运行时开关关掉后完全退回本地磁盘实现，等同于没引这个 jar")
    void disabledFallsBackToCore() {
        runner.withPropertyValues("describeadmin.storage.s3.enabled=false").run(context -> {
            assertThat(context.getBean(StorageProvider.class)).isInstanceOf(LocalFileStorageProvider.class);
            // 两层开关里的运行时那层：连 S3Client 都不应该被创建
            assertThat(context).doesNotHaveBean(S3Client.class);
        });
    }

    @Test
    @DisplayName("业务方自定义的 StorageProvider 优先于插件——插件不能抢业务方的位置")
    void businessBeanWins() {
        runner.withBean("customStorage", StorageProvider.class, NoopStorageProvider::new).run(context -> {
            assertThat(context.getBean(StorageProvider.class))
                    .as("业务方显式注册的 Bean 必须赢过插件，否则无法覆盖框架行为")
                    .isInstanceOf(NoopStorageProvider.class);
        });
    }

    @Test
    @DisplayName("业务方自定义的 S3Client 被沿用，插件不再建自己的")
    void customS3ClientIsRespected() {
        runner.withBean("customS3Client", S3Client.class, AbstractS3Test::newClient).run(context -> {
            assertThat(context.getBeanNamesForType(S3Client.class)).containsExactly("customS3Client");
            assertThat(context.getBean(StorageProvider.class)).isInstanceOf(S3StorageProvider.class);
        });
    }

    @Test
    @DisplayName("auto-create-bucket=true 时，桶不存在会被自动创建")
    void autoCreateBucketCreatesMissingBucket() {
        String freshBucket = "describeadmin-s3-autocreate-" + UUID.randomUUID().toString().substring(0, 8);
        runner.withPropertyValues(
                        "describeadmin.storage.s3.bucket=" + freshBucket,
                        "describeadmin.storage.s3.auto-create-bucket=true")
                .run(context -> {
                    assertThat(context.getBean(StorageProvider.class)).isInstanceOf(S3StorageProvider.class);
                    try (S3Client raw = newClient()) {
                        assertThatNoException().isThrownBy(() -> raw.headBucket(b -> b.bucket(freshBucket)));
                        raw.deleteBucket(b -> b.bucket(freshBucket));
                    } catch (NoSuchBucketException alreadyGone) {
                        // 清理时桶已不在，无所谓
                    }
                });
    }

    /** 只为验证"业务方 Bean 优先"，方法体不需要真的能用。 */
    static final class NoopStorageProvider implements StorageProvider {
        @Override
        public StorageObject put(String key, InputStream content, long size, String contentType) {
            return new StorageObject(key, "noop://" + key, 0, contentType);
        }

        @Override
        public Optional<InputStream> get(String key) {
            return Optional.empty();
        }

        @Override
        public boolean exists(String key) {
            return false;
        }

        @Override
        public void remove(String key) {
        }

        @Override
        public String url(String key) {
            return "noop://" + key;
        }

        @Override
        public String presignedUrl(String key, Duration expiry) {
            return url(key);
        }
    }
}
