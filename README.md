# framework-storage-s3-starter

describeadmin 的可选插件。把框架的文件存储从本地磁盘切到 S3 或 S3 兼容的对象存储（已验证 AWS S3、MinIO、RustFS）。

**文档：[https://describeadmin.github.io/docs/how-to/storage-s3](https://describeadmin.github.io/docs/how-to/storage-s3)**，全部配置项见 [插件配置](https://describeadmin.github.io/docs/reference/configuration/plugins)。

## 引入

```xml
<dependency>
  <groupId>io.github.describeadmin</groupId>
  <artifactId>framework-storage-s3-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

版本号要显式写：`framework-bom` 不管插件的版本，插件有自己的版本线。

```yaml
describeadmin:
  storage:
    s3:
      endpoint: http://10.0.0.20:9000      # AWS S3 留空，改写 region
      access-key: ${S3_ACCESS_KEY}
      secret-key: ${S3_SECRET_KEY}
      bucket: my-app
```

运行时开关是 `describeadmin.storage.s3.enabled`（默认 `true`），关掉后与没有引入这个 jar 完全一样。

## 兼容性

| 插件版本 | 最低框架版本 |
|---|---|
| 1.0.0 | 1.0.0 |

插件启动时检查框架版本：框架比上表要求的旧、或主版本不同（0.x 与 1.x 不互通），应用直接拒绝启动并说明原因。
CI 对表里的每个框架版本各跑一遍完整测试。版本对应关系的总表见 [插件目录](https://describeadmin.github.io/docs/reference/plugins)。

## 开发

```bash
mvn clean test                       # 默认用 Testcontainers 起 MinIO，需要 Docker
# 指向一个已在运行的 S3 兼容服务（如 RustFS），不起容器：
mvn clean test -Ds3.endpoint=http://localhost:9000 -Ds3.access-key=rustfsadmin -Ds3.secret-key=rustfsadmin
mvn clean test -Ddescribeadmin.version=1.0.0
```

测试不用 mock：预签名 URL 能否被真正下载、path-style 寻址、`NoSuchKey` 错误码，mock 掉就验证不到了。

构建只要求 JDK ≥ 17（enforcer 把关，产物固定为 Java 17 字节码）；用 `mvn -v` 看 Maven 实际用的 JDK。
框架有尚未发布到 Central 的版本时，先 `mvn -f ../framework/pom.xml clean install -DskipTests -Dgpg.skip=true` 装到本地仓库。

本插件不继承 `framework-parent`，而是 import `framework-bom`，与业务方的用法相同。插件开发规范见
[编写插件](https://describeadmin.github.io/docs/dev/plugin-dev)，发布照 [发布手册](https://describeadmin.github.io/docs/dev/release) 执行。

## 变更记录与许可证

[CHANGELOG.md](./CHANGELOG.md) · [Apache-2.0](./LICENSE)
