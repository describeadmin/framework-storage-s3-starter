# 更新日志

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的组织方式，
版本号遵循 [SemVer](https://semver.org/lang/zh-CN/)。

每个版本固定分 **Breaking Changes / New Features / Bug Fixes** 三类
（见组织编码规范第 5 节）。没有内容的类别保留标题并写「无」。

> **本插件的版本线与框架独立**。每个版本都必须写明适配的框架版本，见下方各条目的「框架要求」。

## 0.2.0 (未发布)

以 S3 / S3 兼容对象存储承载 `StorageProvider` 的可选插件。

**框架要求：0.2.0 及以上**（用到 `framework-storage-starter` 0.2.0 的 `StorageProvider`
契约与其 `presignedUrl` 扩展点）。

### Breaking Changes

无（首个版本）。

### New Features

- 把 `StorageProvider` 从框架自带的 `LocalFileStorageProvider` 切换到 S3 / S3 兼容对象存储，
  解除本地磁盘实现的硬伤：多实例各写各的盘、容器重建丢文件、对外下载要自己写端点。
  框架核心与业务代码一行都不用改。
- 不引入本插件时行为与从前完全一致；引入后可用 `describeadmin.storage.s3.enabled=false`
  在运行时退回本地磁盘实现——排查"是不是对象存储的问题"不必改 pom 重新打包。此开关关闭时
  连 `S3Client` 都不会创建。
- `presignedUrl(key, expiry)` 返回真正带签名、到期失效的下载地址；私有桶下这才是业务方
  应该用的下载 / 预览链接。`url()` 退化为"公开基地址 / 端点 + 桶 + 键"拼出的字符串。
- 配置项 `describeadmin.storage.s3.*`：`endpoint` / `region` / `access-key` / `secret-key` /
  `bucket` / `path-style-access`（默认 `true`，面向自建 S3 兼容服务）/ `key-prefix`
  （多应用共用一个桶时隔离）/ `public-url-base`（CDN 域名）/ `auto-create-bucket`（默认 `false`）。
  `access-key` 留空回落到 AWS 默认凭证链。
- 固定使用 `url-connection-client` 作为唯一的 AWS HTTP 实现（零额外传递依赖），并排除
  SDK 默认拖进的 `apache5-client` / `apache-client` / `netty-nio-client`——两个实现同时在
  classpath 上，SDK 会在建 client 时抛 "Multiple HTTP implementations found"。
- 客户端校验和策略退回 `WHEN_REQUIRED`（= AWS SDK 2.30 之前的行为）：AWS SDK 新版默认发
  `x-amz-checksum-crc32` 而不再发 `Content-MD5`，较老的 S3 兼容实现不认，会以 400 拒绝。
- 启动期自检框架版本（`FrameworkVersion.requireCompatible`）：装到比
  `REQUIRED_FRAMEWORK_VERSION` 更旧的框架上会**启动失败**并给出可操作的提示。
- `S3StorageProvider` 与核心 `LocalFileStorageProvider` 遵守同一份 `StorageProvider` 语义，
  key 格式校验规则一致（拒绝 `..` 片段 / 前导分隔符 / 反斜杠 / 盘符；读方法按未命中处理不抛异常）。
  唯一有意的行为差异：`put` 的 `size > 0` 时被当作真实 `Content-Length`，对不上是调用方错误
  （S3 流式上传的固有约束）——已在 README 与测试里写明。

### Bug Fixes

无（首个版本）。

### 仓库

- 独立成仓、独立版本线、独立发布（方案 3.1.1 的既定拓扑）。
- POM **不继承 `framework-parent`**，改为 `import framework-bom`——这正是业务方消费框架的姿势。
- CI 按**框架版本矩阵**跑完整测试（Testcontainers MinIO）。
