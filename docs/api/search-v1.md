# Raychi 搜索接入 v1

权威状态：MySQL `articles` 的公开快照和 `search_sync_state`。只同步已发布快照；工作稿保存不会改动搜索版本。发布、再次发布、撤回和删除在同一事务里锁定内容行，更新独立搜索版本和最新目标状态；删除墓碑保留。即使搜索未启用也保留后续变更。

## 公开接口

`GET /api/v1/public/search?q=数据库&type=article&offset=0&limit=20`

- 无须登录；可选 type 仅 `article|post`。默认 offset=0、limit=20，最大 offset=200000、limit=50。
- q 去两端空白后 2～200 字符，不支持单字 CJK 搜索；标点当作关键词分隔符，操作符没有查询语言含义。
- 200 `{hits:[{id,version,type,title,snippet,score,href}],nextOffset:number|null}`。纯文本 snippet，由前端转义；不返回精确总数。
- 排序与 nextOffset 来自内核。MySQL 一次批量查询剔除非公开、类型不符或版本不一致的命中。因此页面可能不足 limit、甚至为空而 nextOffset 非空；调用者仍可继续。
- 无命中 200 空数组；非法参数 400 `VALIDATION_FAILED`；不可用/超时/服务鉴权错误 503 `SEARCH_UNAVAILABLE`。统一本站错误结构，成功和错误都禁止缓存。
- 返回 href 使用当前公开内容稳定 slug：`/writing/{slug}` 或 `/posts/{slug}`。

## 同步与运维

后台默认每 2 秒最多取 100 行，单请求超时 3 秒。2、4、8…60 秒退避处理网络、429、5xx；其他 4xx 进入 ERROR，排查后手动重新排队。响应只能确认相同 document_id+version，不能覆盖同步期间产生的新版本。重复写入依赖内核幂等；进程停止不丢失已提交目标。

`RAYCHI_SEARCH_ENABLED=true`、`RAYCHI_SEARCH_URL=http://127.0.0.1:8091`、`RAYCHI_SEARCH_NAMESPACE=raychi-public`、`RAYCHI_SEARCH_TOKEN`（密钥来自环境，不能提交或传给浏览器）。内核调用者需要 `search:read,search:write,search:maintain` 和该命名空间权限。启动时补齐尚无目标状态的全部内容。每个 wellspring 数据库只启动一个同步实例；恢复期间也只允许该实例作为 namespace 写入者。

以下接口需要管理员会话，POST 同时需要 CSRF：

- GET `/api/v1/admin/search/status`：按状态计数和最早 updated_at（积压年龄可据此计算）。不含内容或密钥。
- GET `/api/v1/admin/search/export`：MySQL REPEATABLE_READ 一致性快照 `{entries:[{id,version,action,document?}]}`，包含墓碑。导出只备份，不改变同步确认状态。
- POST `/api/v1/admin/search/restore`：等待当前同步批次完成并暂停本实例 worker；补齐内容目标 → 一致性快照 → 调内核全量替换 → 按导出版本逐行 CAS 确认 → 无论成败恢复 worker。全量导入超时 120 秒。期间发布照常提交；更新过的行保留 PENDING。
- POST `/api/v1/admin/search/retry`：重新排队 ERROR/RETRY，返回 requeued。
- DELETE `/api/v1/admin/contents/{id}?expectedVersion=N`：带乐观锁硬删除内容及资产数据库引用，204；保留搜索 DELETE 墓碑。实际文件回收沿用存储运维，暂不自动清理。

`node scripts/search-maintenance.ts status|export|restore|retry [export-file]` 使用环境中的 `RAYCHI_API_URL`,`RAYCHI_ADMIN_USER`,`RAYCHI_ADMIN_PASSWORD`。导出正文文件默认权限 0600，应置于私有备份目录并定期删除。密码和服务 token 不进入日志。

恢复动作应使用此命令的 restore：它同时协调 worker，不能在运行中的同步实例旁边直接用过期导出覆盖内核。数据库丢失时恢复后，回放发布期间留下的新目标；内核 local rebuild 仅从自己的 SQLite 源文档重建 FTS，不能恢复丢失源数据。
