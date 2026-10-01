# wellspring

**Part of Raychi · 内容服务**。Spring Boot 3.5 + Java 21 + MySQL，提供站长会话、文章工作稿与发布快照、图片上传和授权读取。访客页面在 [lantern](https://github.com/raychi-space/lantern)，管理界面在 [inkwell](https://github.com/raychi-space/inkwell)。Hugo 是待替换的旧博客处理方式，不是运行依赖。

## 本机运行

需要 Java 21、Maven 3.9+、Docker Compose。先复制并填写环境变量；数据库密码和站长 BCrypt 哈希不要提交进 Git。

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

模块内部职责见[模块说明](docs/module.md)，v0.2 范围见[任务文档 PR #3](https://github.com/raychi-space/wellspring/pull/3)；Agent 长期约定见 [AGENTS.md](AGENTS.md)。跨仓任务按[项目流程](https://github.com/raychi-space/raychi/blob/main/docs/workflow.md)处理；实时状态看[Raychi Project](https://github.com/orgs/raychi-space/projects/1)及对应 Issue/PR。

## 目录约束

`src/main/java/space/raychi/wellspring/` 下的启动类保持在根包，以覆盖所有子包的组件扫描。业务代码按 `article/`、`asset/`、`auth/`、`site/` 分组；`api/` 存放跨业务的错误响应与异常。控制器处理 HTTP 契约，服务处理业务流程与事务。跨领域调用应经过明确的服务接口，不依赖另一领域的控制器。

数据库版本脚本保存在 `src/main/resources/db/migration/` 和 `src/main/java/db/migration/`，应用配置在 `src/main/resources/`，集成测试在 `src/test/`，公开接口契约在 `docs/api/`。新增接口保持 `/api/v1` 版本前缀；生产数据与本地密钥不进入仓库。

## 搜索服务

配置、公开查询、V6 迁移、来源恢复和运维命令见 [搜索接入 v1](docs/api/search-v1.md)。搜索默认关闭；启用前部署独立 search-core 0.2.0 并配置服务端密钥。

## 写作助手

[Agent 接入 v1](docs/api/writing-agent-v1.md) 提供服务商/助手配置代理、站主写作任务和结构化建议。默认关闭；部署独立 agent-core 后填写 RAYCHI_AGENT_ENABLED/URL/TOKEN。浏览器通过本站会话和 CSRF 操作，密钥保存在内核加密存储，生成建议不保存或发布文章。
