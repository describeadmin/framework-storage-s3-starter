# framework-storage-s3-starter

describeadmin 的**可选插件**：把 `StorageProvider` 从框架自带的本地磁盘实现切到
S3 / S3 兼容对象存储。

不引它，框架用 `LocalFileStorageProvider`（文件落在应用进程所在机器的磁盘上），
行为与从前完全一致。引了它，本地磁盘实现的硬伤消失：

| | 本地磁盘（框架自带） | 本插件 |
|---|---|---|
| 多实例部署 | **各写各的盘**，A 实例存的文件 B 实例读不到 | 共享对象存储 |
| 应用重启 / 容器重建 | 未挂持久卷时**文件丢失** | 与应用生命周期无关 |
| 对外下载 | 需要业务方自己写一个下载端点 | `presignedUrl()` 直接给限时签名地址 |
| 容量 | 受单机磁盘限制 | 由对象存储服务承担 |
| 外部依赖 | 无 | 一个 S3 / S3 兼容服务 |

框架核心与业务代码**一行都不用改**——上层只依赖 `StorageProvider` 接口。

已验证可用的后端：AWS S3、MinIO、[RustFS](https://rustfs.com/)。其它声称"S3 兼容"的
服务（Ceph RGW、阿里云 OSS 的 S3 网关等）大概率也能用，接入后请自行跑一遍冒烟。

---

## 兼容性

| 插件版本 | 最低框架版本 | 说明 |
|---|---|---|
| 0.2.0 | **0.2.0** | 用到 `framework-storage-starter` 0.2.0 的 `StorageProvider` 契约与其 `presignedUrl` 扩展点 |

这张表是**可执行的**：

- 插件在启动时自检框架版本（`FrameworkVersion.requireCompatible`），装到更旧的框架上会
  **启动失败**并给出一句能照着做的提示，而不是等到某个上传请求走到那行代码才
  `NoClassDefFoundError`
- CI 对表中每个框架版本各跑一遍完整测试（见 `.github/workflows/ci.yml`）

> ⚠️ **0.x 期间请逐版本核对**。SemVer 对 `0.x` 不作任何保证。

## 接入

```xml
<dependency>
  <groupId>io.github.describeadmin</groupId>
  <artifactId>framework-storage-s3-starter</artifactId>
  <version>0.2.0</version>
</dependency>
```

版本号需要显式写。**框架的 `framework-bom` 不仲裁插件版本**——插件有自己的版本线，
让 BOM 按框架版本去解析插件，会解析到一个根本不存在的制品。

### 配置

```yaml
describeadmin:
  storage:
    s3:
      enabled: true                 # 运行时开关，关掉后完全等同于没引这个 jar（退回本地磁盘实现）
      endpoint: http://localhost:9000   # S3 服务端点；用真正的 AWS S3 时留空，由 region 推导
      region: us-east-1             # S3 兼容服务通常不校验，但 SDK 签名需要，不能为空
      access-key: rustfsadmin       # 留空则回落到 AWS 默认凭证链（环境变量 / ~/.aws / 实例角色）
      secret-key: rustfsadmin
      bucket: describeadmin         # 必填，没有默认值
      path-style-access: true       # 自建服务（MinIO/RustFS）几乎都要 path-style；AWS S3 请设 false
      key-prefix: ''                # 多个应用共用一个桶时用它隔离，如 "app-a/"
      public-url-base: ''           # url() 的公开基地址（CDN 域名等）；留空则用"端点+桶"拼
      auto-create-bucket: false     # 启动时桶不存在是否自动建；生产建议 false，开发/测试可 true
```

`enabled=false` 时插件完全不装配，连 `S3Client` 都不会创建，框架退回
`LocalFileStorageProvider`——排查"是不是对象存储的问题"时改一行配置重启即可，
不必改 pom 重新打包。

### 用法

业务代码注入 `StorageProvider` 即可，与用本地实现时写法完全相同：

```java
@Autowired StorageProvider storage;

// 上传：知道大小时务必传准确的 size（走流式上传）；不知道时传 0 或负数（会先在内存里缓冲整个流）
StorageObject object = storage.put("avatar/1.png", inputStream, contentLength, "image/png");

// 下载 / 预览地址：私有桶下 url() 通常不可直接访问，用 presignedUrl 取限时签名地址
String link = storage.presignedUrl("avatar/1.png", Duration.ofMinutes(15));

storage.get("avatar/1.png");     // Optional<InputStream>，未命中返回空而非异常
storage.exists("avatar/1.png");
storage.remove("avatar/1.png");  // 幂等，对象不存在也静默返回
```

### 与本地磁盘实现的一处行为差异

`put(key, content, size, contentType)` 的 `size` 参数：

- **本地实现**忽略它，以实际落盘字节数为准（传错也能自我纠正）
- **本插件**在 `size > 0` 时把它当作真实 `Content-Length` 走流式上传——S3 的同步上传
  必须提前知道准确长度，传的值与流实际字节数对不上会直接失败。需要"大小未知"语义时
  请传 `size <= 0`，此时插件会把整个流读进内存再上传（**大文件务必传准确 size**，否则堆会被吃满）

## HTTP 客户端

本插件固定使用 AWS SDK 的 `url-connection-client`（基于 JDK 的 `HttpURLConnection`，
零额外传递依赖）。需要连接池吞吐的业务方可以：排掉本插件对 `url-connection-client` 的依赖、
换成 `apache-client`，并**注册自己的 `S3Client` Bean**——插件的所有 Bean 都带
`@ConditionalOnMissingBean`，业务方注册的一定赢。

## 关于校验和头（老 S3 兼容服务的坑）

AWS SDK v2 自 2.30 起默认对 `PutObject` / `DeleteObjects` 等发送
`x-amz-checksum-crc32` 而不再发 `Content-MD5`。较老的 S3 兼容实现不认这个头，
会以 `400 Missing required header ... Content-Md5` 拒绝。本插件已把客户端的
校验和策略退回 `WHEN_REQUIRED`（= 2.30 之前的行为），对 AWS S3 也完全安全。

## 开发

```bash
# 框架尚未发布对应版本时，先从框架仓装一份到本地仓库
mvn -f ../framework/pom.xml clean install -DskipTests

mvn clean test                       # 默认用 Testcontainers 起 MinIO，需要 Docker
# 指向一个已在运行的外部 S3 兼容服务（如 RustFS），跳过容器：
mvn clean test -Ds3.endpoint=http://localhost:9000 -Ds3.access-key=rustfsadmin -Ds3.secret-key=rustfsadmin
mvn clean test -Ddescribeadmin.version=0.3.0   # 针对另一个框架版本跑，即 CI 矩阵做的事
```

构建**不要求特定的 JDK 版本**，唯一前提是 **JDK >= 17**（由 enforcer 的 `requireJavaVersion`
把关）。用 `mvn -v` 看 Maven 实际使用的 JDK，**不要用 `java -version`**。

测试用真实的 S3 兼容服务（Testcontainers MinIO / 外部 RustFS），不用 mock：本模块的价值全在
"与真实 S3 语义一致"——预签名 URL 能否被 HTTP 真正拉到、path-style 寻址、`NoSuchKey` 的错误码、
`DeleteObject` 的幂等性，恰恰是 mock 掉之后就再也验证不到的东西。

## 相关文档

- 插件准入规范与目录：docs 仓 `registry.md`
- 编码规范：`CLAUDE.md`（组织级母本在 docs 仓，本仓为副本，**不要单独修改**）
- 发布步骤：docs 仓 `RELEASE.md`——发到 Maven Central 的版本不可撤回、不可覆盖

## License

Apache License 2.0
