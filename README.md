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

### 管理员昵称

`PUT /api/v1/auth/nickname` 使用当前管理员会话和 CSRF，接受 `{nickname}`。服务端去除首尾空白，要求 1–100 个 UTF-16 码元，并拒绝控制字符。成功返回更新后的会话；重复保存同一昵称也返回成功。昵称只供管理台显示，登录用户名、密码、凭据版本与已有会话保持原值。

会话查询和登录响应均包含 `nickname`。已有账户初始显示登录用户名，匿名会话返回 `null`。PostgreSQL V3 / MySQL V16 为管理员账户增加可空昵称。离线 MySQL → PostgreSQL 工具支持 V14–V16 源库，V16 昵称与其他字段一并复制和校验。

### 访问地图

`GET /api/v1/admin/analytics/map` 为已登录管理员返回按地区聚合的地图点。按日期和筛选条件在原始记录保留期内按 IP 去重，响应包含覆盖期、未定位数和聚合计数。字段见 OpenAPI。访问统计内核需支持对应的 v2 地图接口。
