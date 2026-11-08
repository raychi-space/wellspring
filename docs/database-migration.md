# MySQL V14 → PostgreSQL 17

目标是保留现有站点行为与数据。PostgreSQL V1 是 MySQL V14 的等价建库基线，不复用 MySQL 的 Flyway 校验记录；旧 SQL/Java 迁移保持原样。新 PostgreSQL 库由 Flyway 管理自己的后续版本，MySQL 历史保留于原库与一致性备份。

## 数据和配置

- 所有 16 张业务表整体迁移，包括管理员哈希/凭据版本、工作稿和公开快照、修订、附件元数据、删除队列、搜索同步状态、设置与拾阅。UUID、公开地址、版本及空值保持原值。
- 正文、JSON 文本保持字节内容，不在迁移时重新生成快照、修订、摘要或翻译；附件文件与各内核 SQLite/主密钥由部署一致性备份配套保护，不由本工具读写。
- 时间列使用 `TIMESTAMP(6) WITH TIME ZONE`，迁移两端会话均为 UTC，JDBC 显式以 UTC 读取/写入微秒精度的时间。
- PostgreSQL ICU 排序规则 `raychi_ai_ci` 保留文本比较的大小写/重音不敏感行为。标签名称及公开标签索引使用 `C` 比较，保留 `Tech`/`tech`；管理员标签筛选以 JSON 值包含判断，不能退化为子字符串匹配。不同数据库的全部 Unicode 排序顺序不承诺逐字相同，核心比较、分页和发布路径在真实 PG 上验证。
- 仍使用 `RAYCHI_DB_URL/USER/PASSWORD` 三项私有连接配置。按实际 JDBC 类型选择迁移目录，不另维护散落的方言开关。服务端 PG 管理账户与应用账户分开；后续 AIHOT 使用独立数据库和角色。

## 一次性离线工具

`space.raychi.wellspring.tools.DatabaseTransfer` 不启动 Spring、服务器或任务。配置通过进程环境提供，禁止在日志或命令行写密码：

```text
RAYCHI_MIGRATION_SOURCE_URL=jdbc:mysql://<mysql>:3306/raychi?connectionTimeZone=UTC
RAYCHI_MIGRATION_SOURCE_USER=<read-capable-source-user>
RAYCHI_MIGRATION_SOURCE_PASSWORD=<private>
RAYCHI_MIGRATION_TARGET_URL=jdbc:postgresql://<postgres>:5432/<new-database>
RAYCHI_MIGRATION_TARGET_USER=<target-database-owner>
RAYCHI_MIGRATION_TARGET_PASSWORD=<private>
RAYCHI_MIGRATION_TARGET_CONFIRM=<exact-new-database-name>
```

打包后运行：

```bash
java -Xmx128m -Dloader.main=space.raychi.wellspring.tools.DatabaseTransfer \
  -cp target/wellspring-0.1.0-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher --copy
# 相同配置，重新进行只读全字段核对
java -Xmx128m -Dloader.main=space.raychi.wellspring.tools.DatabaseTransfer \
  -cp target/wellspring-0.1.0-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher --verify
```

`--copy` 要求目标 schema 没有任何业务表或 Flyway 表，先运行 PG 基线，然后在单个事务中替换初始种子值、按外键顺序复制和校验。已有表的目标会被拒绝；不要通过清空已有内容库来绕过。失败后源库保持不变，目标复制事务回滚；基线可能已经存在，重试应使用新的空目标或在明确隔离环境中重建目标。

工具只接受已完成全部 V1–V14 的 MySQL 来源，发现未知表/列即失败。逐表校验行数及全部字段规范化后的 SHA-256 多重集，包括 NULL、Unicode、布尔和时间；不依赖两库不同的排序规则。单表校验上限 100 万行。PG 不可表示的内容（例如 NUL 字节）会使复制失败，不静默删除或替换。输出只有表名、行数和指纹，不输出正文/密码哈希；异常信息脱敏，失败不继续切换。

源库只读一致性事务不能替代切换时停写；若用户或定时任务仍写入，复制后可能出现遗漏。正式切换必须先停止所有应用写入者，并保留可恢复的整组快照。

## 验证

普通 `mvn test` 保留 H2/模拟测试。提供 `RAYCHI_TEST_POSTGRES_URL/USER/PASSWORD` 后，Spring 应用测试切换到真实 PostgreSQL；URL 数据库名必须以 `_test` 结尾，每个上下文创建独立 schema、连接池最多 3 个连接。

真实跨库用例另需 `RAYCHI_MIGRATION_TEST_MYSQL_URL/USER/PASSWORD`，来源必须是独立 `_test` 库；该用例会清空这个测试库。它覆盖 16 表复制、微秒/Unicode/JSON/凭据保留、禁止覆盖已有目标、篡改检出及目标事务失败回滚。不要把测试变量指向任何现有内容库。

```bash
mvn -B verify
```

CI 分别运行 PG 和 MySQL 兼容组合。现有 H2 迁移单测及 Mockito 测试仍保留，不将这些结果描述为真实数据库验证。线上迁移步骤、一致性备份和恢复证据属于私有运维仓，不复制到此公开模块。
