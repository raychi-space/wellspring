<a id="wellspring--raychi-内容服务入口"></a>

# wellspring · RayChi 内容服务

先读 [README](README.md)，按任务读[模块说明](docs/module.md)及对应 `docs/api/` 文档；现行站点定义为[语义契约](docs/api/contract-v0.2.md)和[OpenAPI](docs/api/openapi-v0.2.json)，旧文章兼容见 [v0.1](docs/api/contract-v0.1.md)。核对任务指定提交与工作树扩展。

新需求先检查相关需求、设计/ADR 与现有代码和公共能力，提出范围、复用方案、接口影响和验收方式，获得用户明确实施确认后再修改功能代码、配置或数据库。重大偏离重新确认；确认范围内持续执行，不重复审批常规细节。部署/线上启用另需授权。

跨仓规则见[开发流程](https://github.com/raychi-space/raychi/blob/main/docs/workflow.md#文档驱动开发)、[架构](https://github.com/raychi-space/raychi/blob/main/ARCHITECTURE.md)、[文档地图](https://github.com/raychi-space/raychi/blob/main/docs/README.md)和[验收](https://github.com/raychi-space/raychi/blob/main/docs/acceptance.md)。这些项目资料为私有；无权限时从本仓和授权提供的相关资料明确范围，由负责人补必要约束，不能把 404 当作不存在或自行重定义跨仓规则。文档 PR 尚未合并时，使用本仓 [README 的审阅入口](README.md#系统文档审阅入口)。

本仓拥有内容状态、公开快照、认证、附件、迁移与站点 HTTP 契约；内核业务映射归本仓，通用执行与内核状态归其所有者。改请求/响应/权限/错误/兼容行为，同一 PR 更新所属 OpenAPI、语义说明和相关验证，消费方评审。不能跨库或让模型直接发布内容。

验证按 README/CI 的 mvn test/mvn package；权限、接口、迁移和事务变化做相称检查，区分 H2、MockMvc 与真实 MySQL/HTTP 结果。纯文档修改只查内容、路径、链接与差异。PR 记录版本、命令、实际结果及未覆盖项，同步说明；单仓通过不代表主功能完成。不得提交凭据、附件或生产数据。GitHub 操作优先 gh CLI。
