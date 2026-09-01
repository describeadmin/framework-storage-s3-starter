package io.github.describeadmin.storage.s3.autoconfigure;

import io.github.describeadmin.common.api.FrameworkVersion;
import io.github.describeadmin.storage.api.StorageProvider;
import io.github.describeadmin.storage.autoconfigure.FrameworkStorageAutoConfiguration;
import io.github.describeadmin.storage.s3.core.S3StorageProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * framework-storage-s3-starter 的自动配置。
 *
 * <p><b>装配顺序</b>：框架核心的 {@link FrameworkStorageAutoConfiguration} 用
 * {@code @ConditionalOnMissingBean(StorageProvider.class)} 提供本地磁盘兜底，
 * 而该条件<b>只检查当前已注册的 Bean 定义</b>。本插件若晚于核心被评估，核心的
 * {@code LocalFileStorageProvider} 已经注册，本插件的 {@code StorageProvider} Bean
 * 就会被自己的 {@code @ConditionalOnMissingBean} 挡掉——<b>引了插件却完全没生效，
 * 且启动毫无异常</b>。因此本类声明 {@code before = FrameworkStorageAutoConfiguration.class}
 * （直接引用类：framework-storage-starter 是本插件的编译期依赖，必然存在）。
 *
 * <p>与 cache-redis 插件不同的是，本插件<b>不</b>依赖任何 optional 模块，
 * 因此没有 {@code beforeName} 字符串形式的声明。
 */
@AutoConfiguration(before = FrameworkStorageAutoConfiguration.class)
@ConditionalOnClass(S3Client.class)
@ConditionalOnProperty(prefix = "describeadmin.storage.s3", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FrameworkStorageS3Properties.class)
public class FrameworkStorageS3AutoConfiguration {

    /**
     * 本插件要求的最低框架版本。
     *
     * <p>手工声明，不从插件自身版本推导（插件独立成仓后两者无对应关系）。
     * 本插件用到 {@code framework-storage-starter} 0.2.0 引入的 {@link StorageProvider} 契约
     * 与其 {@code presignedUrl} 扩展点，用到旧框架会在<b>运行期</b>抛
     * {@code NoClassDefFoundError} / {@code NoSuchMethodError}，而不是启动时。
     * 用到框架新增 SPI 时必须同步上调本常量。
     */
    public static final String REQUIRED_FRAMEWORK_VERSION = "0.2.0";

    public FrameworkStorageS3AutoConfiguration() {
        // 放在构造函数里：条件都满足、真要装配本插件时才检查。
        // enabled=false 时本类根本不会被实例化，不该因版本不匹配把应用打死
        FrameworkVersion.requireCompatible("framework-storage-s3-starter", REQUIRED_FRAMEWORK_VERSION);
    }

    /**
     * 同步 {@link S3Client}。
     *
     * <p>{@code @ConditionalOnMissingBean}：业务方想用连接池（apache-client）或异步客户端时，
     * 自己注册一个 {@code S3Client} Bean 即可覆盖，本插件让位。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public S3Client describeadminS3Client(FrameworkStorageS3Properties properties) {
        S3ClientBuilder builder = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(properties.getRegion()))
                // AWS SDK v2 自 2.30 起默认对 PutObject/DeleteObjects 等发送 x-amz-checksum-crc32
                // 而不再发 Content-MD5。较老的 S3 兼容实现（旧版 MinIO、Ceph RGW、部分厂商网关）
                // 不认这个头，会以 400 "Missing required header ... Content-Md5" 拒绝。
                // 退回 WHEN_REQUIRED（=2.30 之前的行为）：只在操作强制要求时才算校验和，此时用
                // Content-MD5。对真正的 AWS S3 也完全安全。
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isPathStyleAccess())
                        .build());
        applyEndpointAndCredentials(properties, builder::endpointOverride, builder::credentialsProvider);
        return builder.build();
    }

    /**
     * {@link S3Presigner}，用于生成预签名下载地址。它自己不发起网络调用，无需 HTTP 客户端。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public S3Presigner describeadminS3Presigner(FrameworkStorageS3Properties properties) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(properties.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isPathStyleAccess())
                        .build());
        applyEndpointAndCredentials(properties, builder::endpointOverride, builder::credentialsProvider);
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean(StorageProvider.class)
    public StorageProvider s3StorageProvider(S3Client s3Client, S3Presigner s3Presigner,
                                             FrameworkStorageS3Properties properties) {
        if (!StringUtils.hasText(properties.getBucket())) {
            throw new IllegalStateException(
                    "启用了 framework-storage-s3-starter 但未配置 describeadmin.storage.s3.bucket。"
                            + "临时不想用对象存储时请设 describeadmin.storage.s3.enabled=false 退回本地磁盘实现。");
        }
        S3StorageProvider provider = new S3StorageProvider(
                s3Client, s3Presigner, properties.getBucket(),
                properties.getKeyPrefix(), resolveUrlBase(properties));
        if (properties.isAutoCreateBucket()) {
            provider.createBucketIfMissing();
        }
        return provider;
    }

    @FunctionalInterface
    private interface EndpointSetter {
        void accept(URI uri);
    }

    @FunctionalInterface
    private interface CredentialsSetter {
        void accept(StaticCredentialsProvider provider);
    }

    private static void applyEndpointAndCredentials(FrameworkStorageS3Properties properties,
                                                    EndpointSetter endpointSetter,
                                                    CredentialsSetter credentialsSetter) {
        if (StringUtils.hasText(properties.getEndpoint())) {
            endpointSetter.accept(URI.create(properties.getEndpoint()));
        }
        if (StringUtils.hasText(properties.getAccessKey())) {
            credentialsSetter.accept(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())));
        }
        // accessKey 留空 → 不设 credentialsProvider，SDK 回落到默认凭证链
    }

    /**
     * 计算 {@link StorageProvider#url} 的基地址（含桶信息，不带尾部 {@code /}）。
     *
     * <p>优先级：显式配的 {@code public-url-base} &gt; 端点 + 桶（按寻址风格） &gt; AWS 官方域名。
     */
    static String resolveUrlBase(FrameworkStorageS3Properties properties) {
        if (StringUtils.hasText(properties.getPublicUrlBase())) {
            return stripTrailingSlash(properties.getPublicUrlBase());
        }
        String bucket = properties.getBucket();
        if (StringUtils.hasText(properties.getEndpoint())) {
            String endpoint = stripTrailingSlash(properties.getEndpoint());
            if (properties.isPathStyleAccess()) {
                return endpoint + "/" + bucket;
            }
            int schemeIdx = endpoint.indexOf("://");
            if (schemeIdx > 0) {
                return endpoint.substring(0, schemeIdx + 3) + bucket + "." + endpoint.substring(schemeIdx + 3);
            }
            return bucket + "." + endpoint;
        }
        return "https://" + bucket + ".s3." + properties.getRegion() + ".amazonaws.com";
    }

    private static String stripTrailingSlash(String value) {
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
