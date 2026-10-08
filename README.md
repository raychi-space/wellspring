# wellspring

**Part of Raychi · 内容服务**。Spring Boot 3.5 + Java 21 + MySQL，提供站长会话、文章工作稿与发布快照、图片上传和授权读取。访客页面在 [lantern](https://github.com/raychi-space/lantern)，管理界面在 [inkwell](https://github.com/raychi-space/inkwell)。Hugo 是待替换的旧博客处理方式，不是运行依赖。

## 本机运行

需要 Java 21、Maven 3.9+、Docker Compose。首次启动先复制并填写环境变量；数据库密码和站长 BCrypt 初始化哈希不要提交进 Git。管理员凭据由 V10 持久化到 MySQL；已有账号时初始化变量不会覆盖密码。

```bash
cp .env.example .env
htpasswd -nBC 12 admin | cut -d: -f2-
# 按提示输入密码，将所得哈希写入 .env 中单引号包裹的 RAYCHI_ADMIN_PASSWORD_HASH，并设置 RAYCHI_ADMIN_USER
docker compose up -d --wait mysql
set -a; source .env; set +a
mvn spring-boot:run
```

默认 API 在 <http://127.0.0.1:8080>，MySQL 映射到本机 `3307`。`RAYCHI_ASSET_DIR` 指向私有附件目录（默认 `./data/assets`），必须与数据库一同备份。数据库由 Flyway 自动迁移。执行 `mvn test` 跑测试，`mvn package` 构建应用。

接口说明见[内容与站点契约 v0.2](docs/api/contract-v0.2.md)、[兼容的文章契约 v0.1](docs/api/contract-v0.1.md)与[图片授权规则](docs/api/assets-v0.1.md)。内容和图片的匿名可见性以当前发布快照为准；管理端通过同源会话 Cookie 与 CSRF 访问。上线时启用安全 Cookie，反向代理同源 `/api`，再单独评估部署资源与备份恢复。

模块内部职责见[模块说明](docs/module.md)，早期 v0.2 范围见[产品范围文档](docs/product-scope-v0.2.md)；当前字段与行为以 `docs/api/` 契约为准，Agent 长期约定见 [AGENTS.md](AGENTS.md)。跨仓任务按[项目流程](https://github.com/raychi-space/raychi/blob/main/docs/workflow.md)处理；实时状态看[Raychi Project](https://github.com/orgs/raychi-space/projects/1)及对应 Issue/PR。

## 目录约束

`src/main/java/space/raychi/wellspring/` 下的启动类位于根包，覆盖全部子包的组件扫描。应用采用 Controller → Service → Mapper 分层：

| 目录 | 职责与限制 |
| --- | --- |
| `controller/` | HTTP 参数绑定、调用 Service、通过 `ApiResponses` 构造响应；不写 SQL、事务或业务判断。 |
| `service/` | 校验、认证、发布快照、图片权限和事务；将 Entity 转为 DTO。 |
| `mapper/` | 基于 JdbcTemplate 执行 SQL 和映射数据库记录；仅接受或返回持久化数据，不依赖 Controller、Service 或 API DTO。 |
| `entity/` | 数据库记录与持久化输入；只在 Service 与 Mapper 内部使用。 |
| `dto/` | HTTP 请求和响应模型，独立于数据库表和实现类；公开内容与管理内容分别定义。 |
| `api/` | 响应工厂、分页结构、业务异常、全局异常处理和请求 ID。 |
| `config/` | Spring Security 等框架配置。 |

成功 JSON 保持 `/api/v1` 的 DTO 和分页契约，由 `ApiResponses` 统一构造 200/201/204；图片响应集中处理缓存、媒体类型及 HEAD。异常经 `ApiErrors` 转换成 `code/message/requestId/fieldErrors`，安全过滤器使用同一格式；`X-Request-Id` 响应头与错误体中的 ID 一致。

数据库迁移位于 `src/main/resources/db/migration/` 和 `src/main/java/db/migration/`，应用配置位于 `src/main/resources/`，集成测试位于 `src/test/`，接口契约位于 `docs/api/`。Flyway 迁移作为独立的数据库升级代码，不经过业务 Mapper。`LayeringTest` 在 `mvn test` 中检查层级依赖，`ApiContractTest` 验证响应结构、错误、图片 GET/HEAD 和会话流程。

## 搜索服务

配置、公开查询、V6 迁移、来源恢复和运维命令见 [搜索接入 v1](docs/api/search-v1.md)。搜索默认关闭；启用前部署独立 search-core 0.2.0 并配置服务端密钥。

## 写作助手

[Agent 接入 v1](docs/api/writing-agent-v1.md) 提供服务商/助手配置代理、站主写作任务和结构化建议。默认关闭；部署独立 agent-core 后填写 RAYCHI_AGENT_ENABLED/URL/TOKEN。浏览器通过本站会话和 CSRF 操作，密钥保存在内核加密存储，对话/改写建议不保存或发布文章。

当前管理台先保存工作稿，再调用 metadata 任务生成并保存发布标题、摘要与地址别名；发布前由站主编辑确认，已发布地址保持不变。使用 `metadataReviewed=true` 发布不会再排队覆盖确认摘要。V7 的发布后摘要任务保留旧客户端兼容：旧发布先成功、后台生成摘要，模型失败不阻断该旧接口发布。停用 Agent 不删除内容和配置，但当前管理台的元数据准备需要可用助手。字段、版本与错误规则见[内容契约](docs/api/contract-v0.2.md)和[写作契约](docs/api/writing-agent-v1.md)。

## 分页与附件回收（2026-10-03）

公开列表在数据库执行筛选、排序与分页。V8 回填已发布标签索引，保持大小写敏感；V9 为删除文章的附件保存回收队列。后台默认每 30 秒只清理已提交的队列，失败保留记录重试；既有未知文件不扫描、不清理。数据库与附件仍需配套备份。分层测试覆盖全部目录中以 Controller/Service 结尾的类，搜索查询经 SearchService 与 SearchVisibilityMapper。

## 修改管理员密码

管理台账户菜单提供修改密码入口，校验旧密码，成功后需在所有设备重新登录。接口与持久化/错误行为见 [v0.2 契约](docs/api/contract-v0.2.md#管理员修改密码)。环境哈希只用于首次初始化，不用于重置已有账号；备份需包含 MySQL 账号表。

## 访问统计

可选独立 waymarks 桥接默认关闭，开启所需配置和采集/报表权限见 [站点统计契约](docs/api/analytics-v1.md)。采集只有有界内存队列，失败不参与内容事务；管理员报表区分 PV、访客日及近似访问。运行 `mvn test` 覆盖同源/CSRF、私有路径、站长/机器人、失败重试及队列满。

## 单篇内容导出

管理员可按已保存版本下载工作稿、最近发布快照及全部所属图片的离线ZIP，附相对Markdown与SHA256清单。认证、容量、版本、缺失/损坏失败和附件边界见 [内容导出v1](docs/api/content-export-v1.md)。导出不写数据，不含外部评论、统计或密钥，也不替代整库备份；`ContentExportTest` 验证真实ZIP/图片/清单、发布隔离、权限与256/257附件边界。

## 工作稿修订历史

V11记录每篇最近100条工作稿修订，创建/保存/发布/撤回/成功自动摘要/历史恢复与内容事务原子。旧内容首次新变更前保留基线，不生成虚构历史。管理员分页只返回元数据，详情/恢复需认证和版本，恢复只替换工作稿，不改地址或公开快照；见 [修订历史v1](docs/api/content-history-v1.md)。永久删除级联删除修订，附件仍属于原内容。迁移应走完整发布备份/恢复流程；历史不替代文件或数据库备份。`ContentHistoryTest` 覆盖基线/回滚/100条保留/认证CSRF/跨篇与恢复隔离。

## 相关文章

公开文章可读取至多6条相关文章元数据，依据当前发布标签/分类在数据库匹配排序；不读取候选正文或工作稿，不调用模型。默认3条，无匹配返回空数组，未发布目标404。接口、错误与发布隔离见 [相关文章 v1](docs/api/related-articles-v1.md)。

[回收站](docs/api/content-trash-v1.md)：管理员移入/恢复为草稿及明确永久删除；TRASHED不进入普通列表/编辑/导出/历史，正文和图片恢复前均不公开。旧硬删除API兼容保留，无自动清空。V12与修订表/附件目录一起备份。
