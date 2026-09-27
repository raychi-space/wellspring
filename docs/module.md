# wellspring 模块说明

Spring Boot 启动类在根包 `space.raychi.wellspring`。当前 `main` 的文章、附件、认证控制器及服务位于此包，`ApiErrors` 和 `ApiException` 统一错误处理；控制器维护 HTTP 边界，服务承担业务事务。若将业务迁至子包，应在同一 PR 更新说明，并保持启动类能扫描到所有组件。Flyway 脚本在 `src/main/resources/db/migration/`，测试在 `src/test/`。

本仓是 `/api/v1` 契约所有者。请求/响应和错误结构见 [OpenAPI](api/openapi-v0.1.json)，发布语义见[最小契约](api/contract-v0.1.md)，图片权限见[图片契约](api/assets-v0.1.md)。`lantern` 只用公开接口；`inkwell` 用管理接口和会话/CSRF。新增或修改端点时同步 schema、样例、错误/权限测试与兼容顺序，通知消费方评审。数据库和附件目录需一起备份，前端不得共享本仓数据库或 ORM 对象。

`mvn test` 是当前仓库 CI 命令，`mvn package` 构建应用；现有 `PublishingFlowTest` 用 H2 覆盖发布快照图片权限与过期版本冲突。MySQL/真实 HTTP 的跨仓验收需另记录环境、请求、实际结果和各仓提交，不能由 H2 测试替代。流程见[项目文档](https://github.com/raychi-space/raychi/blob/main/docs/workflow.md)。
