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

本仓模块边界见[模块说明](docs/module.md)，v0.2 范围由[任务文档 PR #3](https://github.com/raychi-space/wellspring/pull/3)维护，Agent 长期约定见 [AGENTS.md](AGENTS.md)。跨仓任务按[项目流程](https://github.com/raychi-space/raychi/blob/main/docs/workflow.md)处理；实时状态看[Raychi Project](https://github.com/orgs/raychi-space/projects/1)及对应 Issue/PR；Project 当前为私有。
