# 历史文本内容离线导入

`tools/HistoricalContentImport`用于经核对的历史文章迁移，保留首次发布/最近修改时间；它不启动HTTP、后台任务或模型，不迁移账号、站点设置和其他内核数据库。已有迁移由独立工具处理，不新增公开写入端点，也不开放普通编辑API修改历史时间。

管理员先备份匹配数据库、附件、各内核状态和私有配置。在隔离PostgreSQL预演后，对明确命名的目标数据库执行`--check`（完整试写后回滚）、`--apply`（整批事务提交）和`--verify`（已有内容精确核对，不新增）。`--check`会短时申请锁，不可称为纯只读。运行只接受PostgreSQL完整库名URL，独立变量`RAYCHI_IMPORT_DB_URL/USER/PASSWORD/CONFIRM`中的CONFIRM必须等于URL库名；不读取站点`.env`，不得把密码放到命令行或日志。

构建后使用Spring Boot PropertiesLauncher：

```sh
java -Dloader.main=space.raychi.wellspring.tools.HistoricalContentImport \
  -cp target/wellspring-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  --check /private/path/manifest.json
```

清单JSON格式`{schemaVersion:1,sourceOrigin:"https://old.example",entries:[...]}`。每项包含`sourcePath`（绝对旧页面路径）、`slug`、`title`、`summary`、`bodyMarkdown`、正文UTF-8的`bodySha256`、`category`、`tags`、`publishedAt/modifiedAt`（UTC ISO Instant）。最多1000项、32MB；正文/标题/分类/标签沿用站点容量限制。只接受纯Markdown文本，图片及原始HTML明确拒绝，附件须另行设计迁移，不能静默丢失。工具不访问sourceOrigin，无网络抓取。

每篇ID由来源origin+路径确定。已存在时要求工作稿、公开快照、版本、地址、分类/标签、时间逐项相同；人工编辑后的内容、不同来源占用地址、重复输入或摘要不符均拒绝覆盖。批次失败回滚整个事务。新文章复用ArticleMapper发布和标签索引、ContentHistoryService记录初始PUBLISH修订、SearchState生成持久UPSERT目标；不运行发布摘要模型。搜索Worker在恢复应用后消费目标，公开搜索继续复核权威库。

源正文/清单/备份不得进Git或CI；旧站草稿不因导入自动公开。没有原始日期的独立页面，应在迁移清单明确使用新站首次公开时间，不能伪造旧日期。旧URL、站内链接/锚点、资源、许可说明及域名配置属于跨仓迁移验收，不能仅以CLI成功判断完整迁移。

测试`HistoricalContentImportTest`需要明确隔离的`RAYCHI_TEST_POSTGRES_URL/USER/PASSWORD`，库名必须以`_test`结尾；每例独立schema，覆盖试写回滚、幂等、时间/修订/索引、冲突整批回滚、人工改稿保护和不相关数据保持。普通无PG环境会明确跳过。
