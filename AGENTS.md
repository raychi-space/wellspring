# wellspring · Raychi 内容服务入口

开始任务先读本仓 [README](README.md)、当前 Issue/PR、[Raychi 仓库地图](https://github.com/raychi-space/raychi#仓库地图)、[开发流程](https://github.com/raychi-space/raychi/blob/main/docs/workflow.md)、[接口与联调](https://github.com/raychi-space/raychi/blob/main/docs/integration.md)及[验收规则](https://github.com/raychi-space/raychi/blob/main/docs/acceptance.md)；本仓职责见[模块说明](docs/module.md)。

本仓拥有文章状态/发布快照、认证、附件、迁移与 HTTP API。当前候选接口见[契约 v0.2](docs/api/contract-v0.2.md)及[OpenAPI v0.2](docs/api/openapi-v0.2.json)，兼容的旧文章接口见[契约 v0.1](docs/api/contract-v0.1.md)。任务以 Issue/PR 指定的提交为准。改请求、响应、错误、权限或兼容行为时，同一 PR 更新 OpenAPI、语义说明及相关测试，并请 lantern/inkwell 消费方评审。

改动后按 README 执行 mvn test；涉及 API/权限/迁移时做相称测试，PR 区分测试环境与真实 MySQL/HTTP 结果，关联 Issue 并说明跨仓影响。不要提交凭据、附件或生产数据。本仓检查通过不代表主 Issue 验收通过；联调组合和最终结果在主 Issue 记录。GitHub 操作优先 gh CLI，不可用时用网页。
