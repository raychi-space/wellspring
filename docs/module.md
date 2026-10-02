# wellspring 模块说明

Spring Boot 启动类在根包 `space.raychi.wellspring`。HTTP 控制器放在 `controller/`，只做参数绑定、Service 调用和响应构造。`service/` 下的 `ArticleService` 管理内容工作稿、发布快照和版本冲突，`TaxonomyService` 管理分类标签，`AssetService` 管理图片校验、文件存储与可见性，`AuthService` 管理会话，`SiteSettingsService` 管理配置校验、旧字段兼容与乐观锁。事务边界位于 Service；旧文章端点的类型校验与写入在同一个事务中完成。

`mapper/` 下的四个 Mapper 封装现有 JdbcTemplate SQL、结果映射及数据库写入结果；`entity/` 保存持久化记录与写入数据，Entity 只在 Mapper 和 Service 之间使用。`dto/` 定义传输模型，公开内容和管理内容使用不同 DTO，Controller 不直接返回数据库记录。Service 完成 Entity → DTO 转换。认证使用 Spring Security 的站长账户，无数据库 Mapper。

`api/ApiResponses` 统一构造成功、创建、空响应与二进制响应；`PageResponse` 定义分页契约；`ApiErrors` 统一处理业务异常、参数绑定、媒体类型、数据库冲突与未预期异常。安全过滤器使用相同 `ApiErrorResponse`。`RequestIdFilter` 为请求生成 `X-Request-Id`，错误体和服务日志使用该 ID。未预期异常返回通用 500 信息，详细异常留在日志。框架配置在 `config/`。

业务 Mapper 不负责数据库升级。Flyway 脚本仍在 `src/main/resources/db/migration/` 与 `src/main/java/db/migration/`。测试在 `src/test/`；`LayeringTest` 防止控制器和服务重新引入 JDBC、反向依赖或把 Entity 暴露在控制器。

本仓是 `/api/v1` 契约所有者。当前三类内容与配置的请求/响应见 [OpenAPI v0.2](api/openapi-v0.2.json)及[语义契约](api/contract-v0.2.md)；旧文章接口见 [v0.1](api/contract-v0.1.md)，图片权限见[图片契约](api/assets-v0.1.md)。`lantern` 只用公开接口；`inkwell` 用管理接口和会话/CSRF。新增或修改端点时同步 schema、样例、错误/权限测试与兼容顺序，通知消费方评审。数据库和附件目录需一起备份，前端不得共享本仓数据库或 ORM 对象。

`mvn test` 是当前仓库 CI 命令，`mvn package` 构建应用；`PublishingFlowTest` 和 `ContentFlowTest` 用 H2 覆盖图片权限、内容类型、筛选、配置与版本冲突；`SiteSettingsConcurrencyTest` 验证配置并发；`ApiContractTest` 用 MockMvc 验证 HTTP 结构、错误、图片 GET/HEAD 和登录登出。MySQL/真实 HTTP 的跨仓验收需另记录环境、请求、实际结果和各仓提交，不能由 H2 单测替代。流程见[项目文档](https://github.com/raychi-space/raychi/blob/main/docs/workflow.md)。
