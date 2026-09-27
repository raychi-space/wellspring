# wellspring · Raychi 内容服务

先读 [README](README.md)、[模块说明](docs/module.md)与 [`docs/api/` 契约](docs/api/contract-v0.1.md)。本仓拥有文章状态/发布快照、认证、附件、数据库迁移及 HTTP API；不负责前端页面。OpenAPI 与语义文档是跨仓接口的事实来源，变更请求、响应、错误、权限或兼容行为时同步更新并让消费方评审，按[项目流程](https://github.com/raychi-space/raychi/blob/main/docs/workflow.md)关联 Issue/PR。

改动后运行 `mvn test`；涉及 API 或数据权限时补充/执行相应测试，并在 PR 写明本地与真实 MySQL/HTTP 验证的区别。不要提交 `.env`、凭据、附件或生产数据；私密图片默认不可匿名读取。
