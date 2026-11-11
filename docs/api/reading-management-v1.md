# 拾阅管理 v1

本扩展只在 AIHOT 模式、配好独立管理凭据且上游安装管理模块时可用。公开拾阅契约保持 [discovery v1](reading-discovery-v1.md)；旧来源/翻译写接口继续返回 409。配置、任务和账本的权威仍为 AIHOT，本站不读其数据库、不持久化配置副本、不执行模型循环。字段类型以 [OpenAPI](openapi-v0.2.json) 的 `ReadingManagement*` 为准。

## 权限、接入与错误

浏览器只访问本站 `/api/v1/admin/reading/aihot`，所有请求需要 ADMIN 会话，写操作还需要当前会话 CSRF。内部服务地址沿用 `RAYCHI_READING_AIHOT_URL`；新增 `RAYCHI_READING_AIHOT_MANAGEMENT_TOKEN`，独立 Base64URL 32–128 字符，与 AIHOT API 私有 `AIHOT_MANAGEMENT_TOKEN` 一致。凭据不放前端、镜像或模型配置；模型密钥仍只加载在 AIHOT Worker。

`GET /admin/reading/integration` 兼容增加 `managementConfigured`，表示本站开关及凭据格式齐备，不承诺上游连接健康；旧字段不变。未配置时管理查询返回 503，公开阅读不受影响。管理请求只走固定路径、GET/POST/PUT 白名单，8 秒超时、1 MiB 响应上限、不跟随重定向、不自动重试写请求。响应 DTO 丢弃上游多余字段，禁止供应商原始报错、配置对象或密钥进入站点响应。

本站匿名查询为 401，缺 CSRF 写为 403。参数校验 400，来源不存在 404，版本冲突/重复来源/暂停来源采集/模型元数据未同步为 409。内部认证失败、上游异常/重定向/非法或超大响应统一安全 503；不转成本站 401 导致管理员被误登出。全部管理结果 `Cache-Control: no-store`。网络结果不明确时先刷新核对，不盲目重放配置或采集操作。

## 端点

下表路径相对于 `/api/v1/admin/reading/aihot`。刷新只调用 GET。

| 方法与路径 | 行为 |
| --- | --- |
| GET `/dashboard?status=failed&kind=all&page=1` | 运行看板、近7日趋势、模型概要、限额、任务每页20条 |
| GET `/sources?q=&enabled=true&health=ok&page=1` | RSS 来源筛选，每页100条；可省略各筛选 |
| GET `/sources/{id}` | 来源配置及用于乐观锁的 `version` |
| POST `/sources` | `name/feedUrl/intervalMinutes`，201；原生新增即启用，首次回填最多3条 |
| PUT `/sources/{id}` | 上述字段加 `enabled/version`，原生版本检查及审计 |
| POST `/sources/{id}/check` | 真实 RSS 网络预览，只返回 `count/elapsedMs`，不保存、不调用模型 |
| POST `/sources/{id}/collect` | 明确提交原生采集任务，202；`jobId=null` 表示同来源已有尚未完成的手动任务 |
| GET `/models` | 配置可用模型、各环节、近7天用量及已配置模型服务限额 |
| PUT `/models/{capability}` | 精确请求 `{"model":"deepseek-flash"}`，或 `{"model":null}` 恢复环境默认 |
| PUT `/budgets/{service}` | `perMinute/perHour/perDay` 三个 JSON 整数，范围0–1000000 |

来源名1–200字符、地址最多2048字符、间隔1–1440分钟。地址必须 HTTPS，不允许用户凭据、fragment 或 token/key/secret 等凭据参数，DNS、重定向和私网访问仍受 AIHOT 原生 SSRF 校验；生产不启用 `ALLOW_PRIVATE_NETWORK_FETCH`。数字字符串、小数、布尔值、溢出数不能作为预算或采集间隔，避免静默截断。

任务 status 为 all/failed/running/ok/skipped/unknown；默认 failed 同时包含结果未知。kind 为 all/schedule/collection/model。来源 health 为 ok/degraded/failing/paused/unknown。page 为1–100000，来源 q 最长100字符，未知查询参数400；任务越界页回到第1页。

## 口径与更新方式

- 今日及7日日期为 `Asia/Shanghai`；包含今天、缺日补0。采集按原始材料发现时间；处理按当天存在分析记录的材料去重，同日重复分析计1，跨日重分析计入相应日期。
- 待处理为原生 new 材料；发布数为当前未撤回发布。来源健康看全部来源，编辑列表只管理 RSS。不能将已发布数当成累计采集数。
- API/Worker 为真实心跳，3分钟内正常；超时显示 stale，无记录 unknown。状态查询不会启动采集。
- 模型报告由 Worker 每分钟提供，仅含注册名、模型名、可用布尔值和环境默认选择；必须匹配当前 Worker 身份和启动时间。尚未同步或心跳过期时不允许切换。只列已配置模型，原生能力/视觉约束仍会校验。
- 切换沿用原生至多60秒缓存，只作用于之后的任务；配置保存在原生 settings 并审计。限额基于每次真实尝试（含重试）滚动检查，任一窗口为0即暂停；限额不是费用额度。
- 用量为近7×24小时 live 模型尝试，成功/失败/结果未知分列；请求数包含尚未完成尝试。Token 只汇总供应商已返回字段，未提供计0；P50/P95直接对实际尝试计算，不平均各组分位数。总览也包含未映射到已知环节的模型尝试。

## 验证与发布顺序

`ReadingManagementServiceTest` 使用受控 HTTP 上游覆盖请求白名单、凭据隔离、响应裁剪、错误/超大体积/无重试和严格整数。跨服务验收使用独立 PostgreSQL、原生 AIHOT API/Worker、本站会话/CSRF和真实管理台；模拟模型只证明预算边界，不代表真实供应商验收。

先经单独发布授权安装固定派生 AIHOT 镜像及私有管理配置，再更新本站、管理台；未配置时新管理页保留未配置状态，旧公开接口兼容。回退管理能力只移除管理凭据/使用旧界面，不清库、不重置账本、不替换原模型密钥。派生镜像与配置维护说明归 raychi `deploy/aihot/management/README.md`。
