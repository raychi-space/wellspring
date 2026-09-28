# wellspring 模块说明

Spring Boot 启动类在根包 `space.raychi.wellspring`。`article/` 管理三类内容工作稿、发布快照、分类标签、公开读取与版本冲突；`asset/` 管理上传、私有文件及按发布快照授权的读取；`auth/` 管理站长会话、CSRF 与访问规则；`site/` 管理独立的站点配置；`api/` 放统一错误响应。控制器维护 HTTP 边界，服务承担业务事务，跨领域调用经服务而非另一领域控制器。Flyway 脚本在 `src/main/resources/db/migration/`，测试在 `src/test/`。

本仓是 `/api/v1` 契约所有者。当前三类内容与配置的请求/响应见 [OpenAPI v0.2](api/openapi-v0.2.json)及[语义契约](api/contract-v0.2.md)；旧文章接口见 [v0.1](api/contract-v0.1.md)，图片权限见[图片契约](api/assets-v0.1.md)。`lantern` 只用公开接口；`inkwell` 用管理接口和会话/CSRF。新增或修改端点时同步 schema、样例、错误/权限测试与兼容顺序，通知消费方评审。数据库和附件目录需一起备份，前端不得共享本仓数据库或 ORM 对象。

`mvn test` 是当前仓库 CI 命令，`mvn package` 构建应用；`PublishingFlowTest` 和 `ContentFlowTest` 用 H2 覆盖图片权限、三类内容、筛选、配置与版本冲突。MySQL/真实 HTTP 的跨仓验收需另记录环境、请求、实际结果和各仓提交，不能由 H2 单测替代。流程见[项目文档](https://github.com/raychi-space/raychi/blob/main/docs/workflow.md)。
