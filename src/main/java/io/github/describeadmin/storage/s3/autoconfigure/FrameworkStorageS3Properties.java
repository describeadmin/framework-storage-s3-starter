package io.github.describeadmin.storage.s3.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * framework-storage-s3-starter 的配置项，前缀 {@code describeadmin.storage.s3}。
 *
 * <p>连接信息为什么不复用 Spring 的某套标准配置：Spring Boot 没有 S3 的标准配置项
 * （AWS 官方的 spring-cloud-aws 有，但那会把一整个 spring-cloud-aws 拖进来）。
 * 因此这里自建一组，命名与包名对齐。
 */
@ConfigurationProperties(prefix = "describeadmin.storage.s3")
public class FrameworkStorageS3Properties {

    /**
     * 是否启用本插件。
     *
     * <p>关闭后框架退回本地磁盘实现（{@code LocalFileStorageProvider}），等同于没有引入本插件——
     * 这是"两层开关"里的运行时那层：编译期由是否引入 starter 决定能力存在与否，
     * 运行时由本项决定是否激活。排查"是不是对象存储的问题"时不必改 pom.xml 重新打包。
     */
    private boolean enabled = true;

    /**
     * S3 服务端点，如 {@code http://localhost:9000}（MinIO / RustFS）或
     * {@code https://oss-cn-hangzhou.aliyuncs.com}（阿里云 OSS 的 S3 兼容网关）。
     *
     * <p>留空表示走 AWS 官方端点，由 {@link #region} 推导——只有真正用 AWS S3 时才留空。
     */
    private String endpoint = "";

    /**
     * 区域。S3 兼容服务通常不校验这个值，但 SDK 的签名计算需要它，不能为空。
     * 对 MinIO / RustFS 保持默认 {@code us-east-1} 即可。
     */
    private String region = "us-east-1";

    /**
     * Access Key。留空则回落到 AWS 默认凭证链（环境变量、{@code ~/.aws/credentials}、
     * EC2/ECS 实例角色等）——只有跑在 AWS 基础设施上时才应该留空。
     */
    private String accessKey = "";

    /** Secret Key。与 {@link #accessKey} 同进退：一个填了另一个也必须填。 */
    private String secretKey = "";

    /** 桶名。必填，没有默认值——桶的命名是业务方的部署决定，框架不替它猜。 */
    private String bucket = "";

    /**
     * 是否使用 path-style 寻址（{@code http://endpoint/bucket/key}）而非
     * virtual-host 寻址（{@code http://bucket.endpoint/key}）。
     *
     * <p><b>默认 {@code true}</b>：本插件的主要场景是自建的 S3 兼容服务（MinIO / RustFS），
     * 它们几乎都要求 path-style。真正用 AWS S3 的业务方应显式设为 {@code false}
     * （AWS 已逐步淘汰 path-style）。
     */
    private boolean pathStyleAccess = true;

    /**
     * 键前缀，会拼在所有对象键前面。
     *
     * <p>多个应用共用一个桶时用它隔离，避免 A 应用的 {@code remove("avatar/1.png")}
     * 删掉 B 应用的同名对象。留空表示不加前缀。末尾的 {@code /} 加不加都行，写入时会规整。
     */
    private String keyPrefix = "";

    /**
     * 对象的公开访问基地址，用于 {@link io.github.describeadmin.storage.api.StorageProvider#url}
     * 的返回值，如 CDN 域名 {@code https://cdn.example.com}。
     *
     * <p>留空时 {@code url()} 退化为"端点 + 桶 + 键"拼出来的地址——私有桶下这个地址
     * 通常并不可访问，业务方应改用
     * {@link io.github.describeadmin.storage.api.StorageProvider#presignedUrl}。
     */
    private String publicUrlBase = "";

    /**
     * 启动时若桶不存在是否自动创建。
     *
     * <p><b>默认 {@code false}</b>。生产环境的桶应由部署流程显式创建并配好权限/生命周期，
     * 让应用有权建桶通常意味着凭证权限过大。开发 / 测试环境（MinIO / RustFS）可设为
     * {@code true} 省去手动建桶。
     */
    private boolean autoCreateBucket = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey == null ? "" : accessKey.trim();
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey == null ? "" : secretKey.trim();
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket == null ? "" : bucket.trim();
    }

    public boolean isPathStyleAccess() {
        return pathStyleAccess;
    }

    public void setPathStyleAccess(boolean pathStyleAccess) {
        this.pathStyleAccess = pathStyleAccess;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
    }

    public String getPublicUrlBase() {
        return publicUrlBase;
    }

    public void setPublicUrlBase(String publicUrlBase) {
        this.publicUrlBase = publicUrlBase == null ? "" : publicUrlBase.trim();
    }

    public boolean isAutoCreateBucket() {
        return autoCreateBucket;
    }

    public void setAutoCreateBucket(boolean autoCreateBucket) {
        this.autoCreateBucket = autoCreateBucket;
    }
}
