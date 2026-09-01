# describeadmin 编码规范与项目约定

> 本文件是组织级的唯一约定来源，供开发者与 AI Agent 共同遵循。
> 本文件由组织根仓库维护，各子仓库以副本或软链复用，**不要在子仓库中单独修改**。
> （自动同步脚本待实现，当前手工同步。）
>
> 设计依据见 `develop_plan.md`；版本事实见 `VERSION_BASELINE.md`。
> 本文件只写"必须遵守的约定"，不重复方案里的论证过程。

---

## 0. 先读这里

本项目是一个**平台/SDK**，不是可复制粘贴的模板。任何改动前先确认自己在哪一层：

- **Platform 层**（`framework`、`framework-*-starter`、`codegen`）——框架团队维护，版本化发布，改动要考虑向后兼容
- **Business 层**——业务方维护，只通过 Maven/npm 依赖引用 Platform 层

**绝对禁止**：把框架源码拷贝进业务仓库；在业务仓库里修改框架代码。

多仓拓扑下，改动一个 SPI 接口通常要同步改 `framework` + 对应 `framework-ext-*` + `frontend`。
动手前先跑 `./scripts/clone-all.sh` 拿到完整上下文。

---

## 1. 版本与环境（硬约束）

| 项 | 取值 | 说明 |
|---|---|---|
| 构建 JDK | **17+** | 任意 JDK ≥ 17 均可；由 enforcer 的 `requireJavaVersion` 兜底，不再用 toolchains 钉版本 |
| 编译目标 | **`maven.compiler.release=17`** | 产物必须能在 Java 17 上运行 |
| Spring Boot | **3.5.16** | 不要升级到 4.x，理由见方案 2.2.1 |
| Jackson | **2.x**（`com.fasterxml.jackson.*`） | 不要使用 `tools.jackson.*`（那是 Jackson 3） |
| Node / pnpm | node `^22.18 \|\| ^24.12`，pnpm `>=11` | |

**构建 JDK 只要求 ≥ 17**——`maven.compiler.release=17` 已保证产物在 Java 17 上运行，
构建用 17 / 21 / 25 都行，`PATH` 上默认是哪个 `java` 无所谓（低于 17 会被 enforcer 拦下）。
`framework` / 各插件 / `codegen` 均**不再用 `maven-toolchains-plugin`**——钉死具体版本只会让没配
`~/.m2/toolchains.xml` 的人以 `Cannot find matching toolchain` 直接构建失败，比它要防的坑更劝退
（见 develop_plan.md 2.2.2 与 VERSION_BASELINE 第八轮）。唯一仍配 toolchains 的是 `sample-app`，
它刻意钉 JDK 17，用最低支持版本验证兼容承诺。

**版本核查纪律**：任何依赖版本以 `https://repo1.maven.org/maven2/**/maven-metadata.xml`
和 `https://registry.npmjs.org/<pkg>` 为准。
**禁止使用 `search.maven.org/solrsearch`**——该索引已陈旧，且会对真实存在的制品返回 `numFound=0`。

---

## 2. 命名规范

| 场景 | 规则 | 示例 |
|---|---|---|
| GitHub 组织 | `describeadmin` | https://github.com/describeadmin |
| Maven groupId | `io.github.describeadmin` | 已在 Central Portal 完成命名空间验证 |
| **Java 包名** | `io.github.describeadmin.*` | 与 groupId **完全一致** |
| Maven artifactId | `framework-<能力>-starter` | `framework-notify-dingtalk-starter` |
| npm 组织 / 包 | `@describeadmin/<能力>` | `@describeadmin/ui` |
| Spring 配置前缀 | `describeadmin.<模块>` | `describeadmin.web.trace.enabled` |

全部标识符统一为 `describeadmin`（无连字符），groupId 与 Java 包名一一对应，无需做任何映射。

包结构约定（每个 starter 内部）：

```
io.github.describeadmin.<模块>
├── autoconfigure/   自动配置类、@ConfigurationProperties
├── api/             对外 SPI 接口与 DTO —— 属于兼容性承诺范围，改动需走 SemVer
├── core/            内部实现
└── util/            工具类
```

`api/` 包下的任何 public 签名变更都是 Breaking Change。

**`util/` 不在兼容性承诺范围内**，因此它只放模块内部用的东西。
真要给业务方用的工具必须放 `api/`——但在放进去之前先读 4.7，
多数"工具类"根本不该进框架。

---

## 3. 数据库约定（强制）

框架产出的 DDL、基类 SQL、生成器模板都会跑在业主的数据库上，业主可能用
MySQL 5.7 或宣称兼容 5.7 的国产化库（达梦 / 金仓 / OceanBase）。因此：

### 3.1 SQL 红线（框架代码与生成器模板必须遵守）

禁止使用：

- 窗口函数（`ROW_NUMBER()`、`RANK()`、`OVER(...)`）
- CTE（`WITH ... AS`）
- 函数索引、不可见列、生成列
- `JSON_TABLE` 及 8.0+ 新增 JSON 函数
- 多列 `ON UPDATE CURRENT_TIMESTAMP`
- 依赖 CHECK 约束的实际生效行为

基线是 **MySQL 5.7 语法的安全子集**——国产化库的 MySQL 兼容通常是子集而非超集，
按 5.7 全集写仍可能失败。

### 3.2 建表规范

```sql
CREATE TABLE sys_example (
  ...
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;
```

`CHARACTER SET` 与 `COLLATE` **必须显式声明**，不依赖服务器默认值
（5.7 默认 `utf8mb4_general_ci`，8.0+ 默认 `utf8mb4_0900_ai_ci`，国产化库各不相同）。

索引键长度按 5.7 的保守限制设计。

### 3.3 主键

默认数据库自增（`IdType.AUTO`），通过
`mybatis-plus.global-config.db-config.id-type` 可切换为雪花 ID。
**不要在实体类上硬编码 `@TableId(type = ...)`**，让全局配置生效。

### 3.4 JDBC 驱动

**框架的任何模块都不得引入 JDBC 驱动依赖**（含 `runtime` scope）。
驱动由业务方在自己的 `pom.xml` 中声明。
`framework-bom` 只提供 `mysql-connector-j` 的 5.7-safe 默认版本 `8.2.0`，业务方可覆盖。

> ⚠️ Connector/J 自 **8.3.0** 起官方不再支持 MySQL 5.7（"8.0 and later"）。
> **8.2.0 是最后一个支持 5.7 的版本**。不要"顺手升级"这个默认值。

### 3.5 分页

MyBatis-Plus 的 `DbType` 必须是配置项，**不要硬编码 `DbType.MYSQL`**。

### 3.6 ⚠️ 字符集：任何读取 SQL/数据文件的环节都必须显式指定编码

本项目已在**两个不同入口**因这一条踩坑（见 VERSION_BASELINE.md 发现 ④、⑤）：

| 入口 | 默认行为 | 必须显式指定 |
|---|---|---|
| `mysql` CLI 导入脚本 | 使用客户端默认字符集 | `--default-character-set=utf8mb4` |
| Spring `spring.sql.init` | 使用**平台默认编码**（中文 Windows 为 GBK） | `spring.sql.init.encoding=UTF-8` |
| 测试 JVM | 平台默认编码 | surefire `-Dfile.encoding=UTF-8` |

**症状极具欺骗性**：中文全部写成乱码，但 `COUNT(*)` 校验完全正常，环境看起来健康。

由此引出一条测试规范——**断言要比对具体值，不要只比对行数**：

```java
// ❌ 查不出字符集问题
assertThat(count).isEqualTo(1);
// ✅
assertThat(nickname).isEqualTo("超级管理员");
```

这与方案 5.4 节"不能仅凭页面看起来正常就下结论"是同一类问题，只是发生在数据层。

### 3.7 ⚠️ MyBatis-Plus 3.5.17 的包路径陷阱（AI 极易写错）

网上绝大多数 MyBatis-Plus 资料、以及 AI 依据旧语料生成的代码，用的都是**旧包路径**。
3.5.x 后期做过两次拆分，以下两条**必须按实测结果写**：

| 类 | ❌ 旧路径（绝大多数资料/AI 输出） | ✅ 3.5.17 实际路径 | 所在制品 |
|---|---|---|---|
| `IService` / `ServiceImpl` | `com.baomidou.mybatisplus.extension.service[.impl]` | `com.baomidou.mybatisplus.spring.service[.impl]` | `mybatis-plus-spring` |
| `PaginationInnerInterceptor` | `...extension.plugins.inner`（仍在此包名下） | 包名不变，但**已移出 `mybatis-plus-extension` 制品** | `mybatis-plus-jsqlparser` |

后者的症状是编译报 `找不到符号: 类 PaginationInnerInterceptor`——包名看着对，
是**制品**没引。`framework-mybatis-starter` 已显式引入 `mybatis-plus-jsqlparser`。

**核实方法**（不要靠记忆或搜索结果）：

```bash
unzip -l ~/.m2/repository/com/baomidou/<制品>/3.5.17/<制品>-3.5.17.jar | grep '<类名>.class'
```

业务代码继承框架的 `BaseService` / `BaseController` 即可，正常情况下不需要直接 import 这些类。

---

## 4. 代码约定

### 4.1 分层与基类

生成器与业务代码产出的是**"薄"代码**，通用逻辑留在框架基类：

```java
public class DeptEntity extends BaseEntity { ... }
public interface DeptMapper extends BaseMapper<DeptEntity> { }
public class DeptService extends BaseService<DeptMapper, DeptEntity> { ... }
public class DeptController extends BaseController<DeptService, DeptEntity> { ... }
```

审计字段（创建人/创建时间/更新人/更新时间/逻辑删除）由 `BaseEntity` 统一承担，
**不要在业务实体里重复定义**。

### 4.2 SPI 扩展

新增登录方式或消息通道时，实现对应接口并注册为 Bean 即可，
框架通过 `List<AuthProvider>` / `Map<String, NotifyChannel>` 自动收集：

```java
public interface AuthProvider {
    String type();
    boolean supports(String type);
    LoginUser authenticate(AuthRequest request);
}
```

**框架核心代码里不允许出现任何具体实现的名字**（"zhengwuding"、"dingtalk" 等字符串
只能出现在对应的 ext 模块内）。

### 4.3 返回值

Controller 一律返回 `Result<T>`，不要自行拼装响应结构。
异常交给全局异常处理器，不要在 Controller 里 try-catch 后返回错误码。

### 4.4 前端测试锚点

所有关键交互元素必须带 `data-testid`，供 AI 自动化测试定位：

```vue
<el-button data-testid="dept-add-btn">新增</el-button>
```

命名格式：`<模块>-<对象>-<动作>`。没有 `data-testid` 的交互元素视为未完成。

### 4.5 权限点

命名固定为 `<模块>:<对象>:<动作>`，动作只有四个：`list` / `add` / `edit` / `remove`。
`seed-rbac.sql` 的种子数据与 codegen 的 `menu-*.sql` 都按这套产出，不要另立一套。

```java
// 继承 BaseController 的五个通用端点自动校验，无需写任何注解
// 前缀由 @RequestMapping 推导：/api/system/user -> system:user

// 自定义端点用 Spring Security 原生注解，不要自造
@PreAuthorize("hasAuthority('system:user:edit')")
@PutMapping("/{userId}/password")
public Result<Void> resetPassword(...) { ... }
```

三条必须知道的事：

1. **推导与授权数据必须对得上**。`apiPrefix` 自定义过、或模块名含下划线时
   （默认 `my_module` → `/api/my-module`，推导得 `my-module`，而权限点是 `my_module`），
   推导结果会与 `menu-*.sql` 登记的权限点错配，**表现为连 ADMIN 都被 403**，
   且错误信息里没有任何东西指向前缀。codegen 已改为直接生成 `permPrefix()` 覆写；
   手写的 Controller 需自行覆写。
2. **新增模块必须同时登记权限点并授权**。只建表不登记 `menu-*.sql`，
   接口会 403 而侧边栏根本没有入口，两个症状都不指向"忘了授权"。
3. **权限快照在登录时确定**。改了角色授权不会对已登录会话实时生效，
   需重新登录或吊销令牌——这是不透明令牌设计的既有取舍。

排查时可用 `describeadmin.security.permission-enabled=false` 临时关闭校验，
但关闭状态下**任何已登录账号都能调用任何接口**，权限点仍会下发给前端用于按钮显隐，
于是界面看起来受控、实际不受控。不要用于生产。


### 4.6 插件

新增能力前先判断它该进核心还是做插件。满足下面任一条 → **必须做插件**：

- 绑定具体外部系统或厂商（钉钉、浙政钉、OSS）
- 引入重量级依赖（Redis、POI、springdoc、Quartz、HTTP client）
- 只有部分项目需要
- 同一能力有多种互斥实现

核心只保留**契约 + 零依赖的默认实现**（现成范式：`CacheProvider` + `InMemoryCacheProvider`、
`TokenStore` + `InMemoryTokenStore`）。核心模块的重依赖由 framework 父 POM 的
`enforce-core-thin` 这条 enforcer 规则在构建期堵死。

**插件一律独立成仓**，不作为 `framework` 仓的 module —— 版本线与发布都不绑定框架。
插件 POM **不继承 `framework-parent`**，改为 `import framework-bom`：那正是业务方消费框架的
姿势，插件用同一套姿势才能提前暴露业务方会遇到的问题。代价是构建配置要自带一份
（`release=17`、surefire 编码、enforcer 的 `requireJavaVersion` + JDBC 驱动 + Jackson 3 三条），
但**不带** `enforce-core-thin` —— 插件的职责就是引入那些重依赖。

由此还有两条容易出错的：

- 插件必须声明**适配的最低框架版本**，并在三处保持一致：`registry.md` 的表格、
  POM 里 import 的 `framework-bom` 版本、代码里的常量 + `FrameworkVersion.requireCompatible()`
  启动期自检。只有第三处真正生效——插件以 `provided` 依赖框架，
  **运行时的框架版本由业务方决定**，不是插件构建时那个
- `framework-bom` **刻意不仲裁插件版本**。插件版本与框架版本无对应关系，
  写进 BOM 会让业务方拿到一个与框架同号、根本不存在的制品

写插件的完整规范见 **`docs/registry.md`**，新增插件必须同时登记到那里和 `repos.yml`。
其中最容易出错、且失败最隐蔽的一条摘在这里：

> 核心用 `@ConditionalOnMissingBean` 提供默认实现，该条件**只检查当前已注册的 Bean 定义**。
> 插件若晚于核心被评估，插件的 Bean 会被自己的条件挡掉——**引了却没生效，启动毫无异常**。
> 插件必须显式声明 `@AutoConfiguration(before = ...)`；对 `optional` 依赖一律用
> `beforeName` 的**字符串**形式，写成 `before = Xxx.class` 会在该模块缺席时加载即失败。

插件必须提供运行时开关（`@ConditionalOnProperty`），关掉后行为与"没引这个 jar"完全一致；
并且必须同时测"不引 = 行为不变"和"引了 = 能力生效"两条路径。

> **例外：`sys_user.mobile`/`sys_user.email`**。按上面的标准，"只有部分项目需要"的手机号/邮箱
> 登录本该整体插件化，但"用户是否有手机号/邮箱"这个属性本身足够稳定、足够通用，
> 值得直接进核心 `sys_user` 表（详见 `docs/LOGIN_MODULE_AUDIT.md` F 项）。
> 真正插件化的是"能不能用手机号/邮箱登录"——这两个字段存在不代表对应的 `AuthProvider`
> 已安装，未装插件时它们只是普通联系方式。核心因此没有违反"不出现具体登录方式"的边界：
> `mobile`/`email` 是数据字段，不是某个厂商或某种验证机制的名字。
> 后续若出现这两个字段覆盖不了的身份属性需求，走框架版本升级新增，不在核心表里预先堆列。

### 4.7 工具类：进核心还是根本不做

4.6 的四条判据是给**能力**用的。工具类大多是零依赖纯函数，四条一条都不命中，
照搬会得出"全都能进核心"的错误结论。工具类用这一条：

> **只有"必须挂在框架装配点上才成立"的工具才进核心**——它要么改变框架已有行为
> （Jackson 序列化、审计填充），要么固化一个框架已经替业务方做过的语义决定
> （分页元信息的边界、树结构、脱敏口径）。
> **纯粹是"少写几行 JDK 调用"的工具一律不做。**

理由是成本结构不对称：这类工具进了 `api/` 就是 SemVer 承诺，**删不掉**，
而收益只是省几行 `LocalDate.now().plusDays(7)`。是净负债。

据此**明确不做**（这些结论已经论证过，不要再翻出来重提）：

- `StringUtils` / `CollectionUtils` / `BeanUtils` / `JsonUtils`——Spring 自带前三个，
  `Objects` / `String.isBlank()` 覆盖其余。框架再出一套的唯一效果是
  "三个 StringUtils 该 import 哪个"
- 传统 `DateUtils`（format / parse / 加减 / 取月初月末）——java.time 时代已无存在价值。
  时间相关真正该做的三件是 4.8 的序列化格式、可注入的 `Clock`、以及把时区口径写进文档
  （DB `DATETIME` → Java `LocalDateTime` → 前端按浏览器本地，链路上不出现
  `Date`/`Timestamp`/`Instant` 就自洽），**没有一件是工具类**
- **不引 Hutool**——~1.5MB 单体依赖、版本节奏与框架无关，且它大量工具做的正是
  本文件明令禁止的事（各种 `Date` 转换）。业务方想用自己引

### 4.8 JSON 序列化约定

由 `framework-web-starter` 的 `FrameworkJsonModule` 统一承担，
开关前缀 `describeadmin.web.json.*`。三条硬约定：

| 约定 | 值 | 为什么 |
|---|---|---|
| `Long` / `long` → **字符串** | 默认开 | 雪花 ID 是 19 位，超过 JS `Number.MAX_SAFE_INTEGER`（16 位），前端 `JSON.parse` 会静默舍入末几位——**列表显示正常，点编辑/删除报「记录不存在」或改错行**。3.3 明确支持切雪花，框架就得保证切过去之后是对的 |
| 时间输出 | `yyyy-MM-dd HH:mm:ss` / `yyyy-MM-dd` / `HH:mm:ss` | 与 codegen 生成的日期选择器 `value-format` 对齐 |
| 时间输入 | **出严进宽**：`T` 与空格分隔都接受，秒与小数秒可省 | 不打断已经在发 ISO 的调用方 |

两条容易踩的：

1. **要保持数字形态的 `Long` 字段，用 `@JsonFormat(shape = JsonFormat.Shape.NUMBER)` 排除。**
   框架自己在 `PageResult` 的 `total`/`current`/`size`/`pages` 上用了它——分页元信息
   不可能接近 2^53，而 `el-pagination` 的 `:total` 要求数字。这是**唯一**的例外，
   新增例外要有同等强度的理由。
2. **绝不要自己声明 `@Bean ObjectMapper`。** 那会顶掉 Spring Boot 的全部默认配置，
   并让业务方的 `spring.jackson.*` 全部失效。要改约定就加 `Module` Bean，
   Boot 会自动收集，且注册在内置 `JavaTimeModule` 之后，天然覆盖。

`BigDecimal` **刻意不转字符串**：金额按 2 位小数计，double 精确到 2^53 分
（约 90 万亿元），远超实际业务范围；转成字符串反而让前端数字输入框与排序都变麻烦。
确有超高精度需求的业务方自行在字段上加 `@JsonSerialize(using = ToStringSerializer.class)`。

### 4.9 ⚠️ Tailwind v4 覆盖不了 Element Plus 默认样式（层叠层陷阱）

Tailwind v4（`@import 'tailwindcss'`）把自己生成的全部工具类放进
`@layer theme, base, components, utilities`。Element Plus 的组件 CSS
（`el-input.css`/`el-select.css` 等）是**未分层**的普通 CSS，且大量组件把
宽度写死为 `width: 100%`（`.el-input`/`.el-select` 都有
`--el-input-width: 100%` 这类声明）。

按 CSS Cascade Layers 规范，**未分层规则无论特异度、无论加载顺序，一律压过
任意已分层规则**。因此想用裸 Tailwind 类覆盖 Element Plus 默认样式的写法
（比如 `class="w-48"` 想让 `ElInput` 变窄）永远不生效——不是类名写错，是
写了也没用。

**症状极具欺骗性**：类确实生成了、确实作用在了对的元素上，`display`/`gap`/
`margin` 这类组件库自己没声明的属性能正常生效，唯独 `width`/`color` 这类
组件库自己也声明了的属性怎么都覆盖不了，容易误判为"没被 Tailwind 扫描到"
去改 `@source` 路径——那条路径本来就是对的。这与 3.6 节"不能仅凭现象猜原因"
是同一类问题。

**修复**：改用 Tailwind v4 的 `!` 重要性前缀（`!w-48` 而不是 `w-48`）。
`!important` 不受层级顺序约束，可靠覆盖组件库的未分层样式。

**核实方法**：组件库对应 CSS 文件里搜不到 `@layer` 包裹就是未分层；
`tailwindcss` 包的 `index.css` 能看到自己是否把 utilities 放进了
`@layer`（v4 默认如此）。

首次踩坑记录：`views/oper-log/index.vue` 筛选栏，三个控件的宽度类从未生效，
`flex-wrap` 逐个把它们挤到下一行，表现为"查询参数一个一行"。

---

## 5. 兼容性与发布

- 遵循 SemVer：只有大版本允许破坏性变更
- 废弃接口先标 `@Deprecated`，至少保留 1–2 个小版本
- `api/` 包下的 public 签名 = 兼容性承诺范围
- 每次发版的 CHANGELOG 必须分 Breaking Changes / New Features / Bug Fixes 三类

---

## 6. 常用命令

```bash
# 拉取全部仓库到工作区
./scripts/clone-all.sh

# 构建（任意 JDK ≥ 17 即可，无需 toolchains）
mvn -f framework/pom.xml clean install

# 跳过 GPG 签名的本地安装
mvn -f framework/pom.xml clean install -Dgpg.skip=true

# 验证 release profile 能产出 Central 要求的三件套（跳过签名，本地可跑）
mvn -f framework/pom.xml clean verify -Prelease -Dgpg.skip=true
```

**发布相关操作一律参照 `docs/RELEASE.md`**，不要凭记忆执行——
发到 Maven Central 的版本不可撤回、不可覆盖。

---

## 7. 给 AI Agent 的额外提示

- **开工前先读 `docs/PROGRESS.md`**——它写「现在到哪了、下一步做什么」。
  多仓拓扑下，"本地已完成但尚未推上 GitHub"是常态，光看远端仓库会得出错误结论
- **改动前先读 `develop_plan.md` 对应章节**，本文件只写结论不写理由，理由在方案里
- 遇到版本问题查 `VERSION_BASELINE.md`，里面记录了已核验的事实和已知的错误信息源
- 本项目多处决策是**有意选择上一代技术**（Spring Boot 3.5 而非 4.x、Jackson 2 而非 3），
  这些不是"过时待升级"，不要主动"帮忙升级"
- 同理，`mysql-connector-j` 的 `8.2.0` 默认值是**有意钉住**的（8.3.0 起不再支持 MySQL 5.7），不要升级
- 发布相关操作参照 `docs/RELEASE.md`，不要凭记忆执行
