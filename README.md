# wellspring

RayChi 的内容 API，使用 Spring Boot、Java 21 和 PostgreSQL。为 [lantern](https://github.com/raychi-space/lantern) 与 [inkwell](https://github.com/raychi-space/inkwell) 提供内容、会话和附件能力。

## 本地开发

需要 Java 21、Maven 3.9+、Docker Compose。首次启动将 [环境示例](.env.example) 复制为 `.env`，为本地数据库设置密码并填写管理员配置。可以用 `htpasswd -nBC 12 admin` 生成 BCrypt 哈希，将冒号后的部分填入 `RAYCHI_ADMIN_PASSWORD_HASH`，在 `.env` 中用单引号包裹。该初始化配置适用于尚无管理员的数据库。

```bash
docker compose up -d --wait postgres
set -a
source .env
set +a
mvn spring-boot:run
```

API 默认在 `http://127.0.0.1:8080`。本地 Compose 将 PostgreSQL 映射到 `5433`。

## 接口与验证

- [站点 OpenAPI](docs/api/openapi-v0.2.json)
- [兼容的文章接口 OpenAPI](docs/api/openapi-v0.1.json)

```bash
mvn test
mvn package
```
