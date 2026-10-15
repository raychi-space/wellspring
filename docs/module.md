# wellspring 模块说明

所有者：wellspring；核对：2026-10-15。本文描述已提交的模块基线，功能开发分支的扩展另行验证。系统边界见[架构](https://github.com/raychi-space/raychi/blob/main/ARCHITECTURE.md)，本仓契约与代码仍需按任务指定提交核对。

Spring Boot 启动类在根包 `space.raychi.wellspring`。通用内容/认证/附件控制器在 `controller/`；搜索、写作、统计、拾阅分别在 `search/`、`ai/`、`analytics/`、`reading/` 功能包。控制器负责参数绑定、应用服务调用、权限/传输适配和响应，不直接操作内容持久化。`service/` 下的 `ArticleService` 管理内容工作稿、发布快照和版本冲突，`TaxonomyService` 管理分类标签，`AssetService` 管理图片校验、文件存储与可见性，`AuthService` 管理会话，`SiteSettingsService` 管理配置校验、旧字段兼容与乐观锁。拾阅拥有来源、链接元数据、同步调度和翻译队列；通用翻译边界在 `ai/TranslationService`。事务边界位于 Service；旧文章端点的类型校验与写入在同一个事务中完成。

`mapper/` 下的 Mapper 封装 JdbcTemplate SQL、结果映射及数据库写入结果；`entity/` 保存持久化记录与写入数据，Entity 只在 Mapper 和 Service 之间使用。`dto/` 定义传输模型，公开内容和管理内容使用不同 DTO，Controller 不直接返回数据库记录。Service 完成 Entity → DTO 转换。认证使用 Spring Security；AdminAccountService / AdminAccountMapper 管理单管理员 MySQL 凭据、首次初始化及乐观版本更新，CredentialVersionFilter 注销过期凭据版本的会话。

`api/ApiResponses` 统一构造成功、创建、空响应与二进制响应；`PageResponse` 定义分页契约；`ApiErrors` 统一处理业务异常、参数绑定、媒体类型、数据库冲突与未预期异常。安全过滤器使用相同 `ApiErrorResponse`。`RequestIdFilter` 为请求生成 `X-Request-Id`，错误体和服务日志使用该 ID。未预期异常返回通用 500 信息，详细异常留在日志。框架配置在 `config/`。

业务 Mapper 不负责数据库升级。Flyway 脚本仍在 `src/main/resources/db/migration/` 与 `src/main/java/db/migration/`。测试在 `src/test/`；`LayeringTest` 防止控制器和服务重新引入 JDBC、反向依赖或把 Entity 暴露在控制器。

本仓是 `/api/v1` 契约所有者。当前文章、帖子与配置的请求/响应见 [OpenAPI v0.2](api/openapi-v0.2.json)及[语义契约](api/contract-v0.2.md)，旧思考兼容为帖子；旧文章接口见 [v0.1](api/contract-v0.1.md)，图片权限见[图片契约](api/assets-v0.1.md)，搜索、写作和拾阅分别见[搜索契约](api/search-v1.md)、[写作契约](api/writing-agent-v1.md)与[拾阅契约](api/reading-v1.md)。搜索经 SearchService/Mapper 校验公开可见性，写作与翻译由 wellspring 组装业务工具与任务来源约束，独立内核各自拥有契约和状态。`lantern` 只用公开接口；`inkwell` 用管理接口和会话/CSRF。新增或修改端点时同步 schema、样例、错误/权限测试与兼容顺序，通知消费方评审。数据库和附件目录需一起备份，前端不得共享本仓数据库或 ORM 对象。

`mvn test` 是当前仓库 CI 命令，`mvn package` 构建应用；`PublishingFlowTest` 和 `ContentFlowTest` 用 H2 覆盖图片权限、内容类型、筛选、配置与版本冲突；`SiteSettingsConcurrencyTest` 验证配置并发；`ApiContractTest` 用 MockMvc 验证 HTTP 结构、错误、图片 GET/HEAD 和登录登出。MySQL/真实 HTTP 的跨仓验收需另记录环境、请求、实际结果和各仓提交，不能由 H2 单测替代。流程见[项目文档](https://github.com/raychi-space/raychi/blob/main/docs/workflow.md)。

访问统计接入见 [wellspring 统计契约](https://github.com/raychi-space/wellspring/blob/main/docs/api/analytics-v1.md)。采集服务和聚合状态归独立 waymarks；应用不访问其 SQLite，浏览器不接收服务 token。

## 接入与后台任务地图

| 位置 | 内部责任与约束 |
| --- | --- |
| `ai/` | AgentClient、WritingService 和 WritingStreams 桥接配置、快照建议及受限 SSE；PublicationSummaryWorker 保留旧发布摘要任务。配置/任务/密钥归内核，本文不另定义协议 |
| `search/` | SearchService 组织查询、批量权威校验和维护；SearchWorker 同步目标、互斥恢复；SearchState 持久最新目标/版本及 CAS 状态 |
| `analytics/` | Controller/Service/Client 校验同源事件、排队/失败隔离、调用通用内核并提供管理员聚合报表 |
| `reading/`、`ai/TranslationService` | 来源白名单、元数据同步、90 天公开窗口及通用翻译边界；不保存外部正文或图片 |
| `service/` 与 `mapper/` | ContentExport/History/Trash、RelatedArticles 和 AssetCleanupWorker 复用内容事务、公开快照、修订与删除队列，不另建内容权威 |

既有 LayeringTest 检查 Controller/Service 后缀及 mapper/entity/dto 的部分依赖规则；SearchState 自有 JdbcTemplate 状态持久化，不属于该后缀检查。不能将测试通过解读为所有文件都完全遵循统一目录分层，也不在本次文档整理中改变这项实现。
