# 站点访问统计 v1

站点仅通过 wellspring 桥接 [waymarks v1](https://github.com/raychi-space/waymarks/blob/9274f8b008cdb60aa48021268bd7952664a0a21a/docs/contract-v1.md)，token 只留在服务端。统计为尽力采集，不参与内容事务、不影响阅读/发布；队列和失败事件不持久化，重启可能丢失尚未送达事件。

## 匿名采集

`POST /api/v1/public/analytics/events`，JSON、同源 Cookie/CSRF，且 Origin 必须精确匹配 `RAYCHI_ANALYTICS_ORIGIN`（不信任请求转发头构造来源）。保持现有 `GET /api/v1/auth/csrf` 初始化方式。

请求仅允许 `{id,path,occurredAt,visitorId?,source?}`：id 为 UUID；path 为无查询串/片段的 `/`、`/writing`、`/posts`、`/archive`、`/more` 或当前已发布的 `/writing/{slug}`、`/posts/{slug}`；occurredAt 为 UTC ISO 时间，最多早 30 分钟/晚 5 分钟；visitorId 可为随机 UUID，不能是 IP/指纹/账号；source 可为来源主机名，不能包含完整 URL。所有其他字段（包括 owner/bot/ip/type）拒绝。没有访客标识仍计 PV。

- 浏览器在成功公开页挂载/路径导航后生成事件，不对 SSR、预取、404、搜索或管理页采集，不上传标题/正文/筛选值。
- 浏览器遵守 DNT/GPC 和本地关闭开关；服务器也排除 DNT=1、Sec-GPC=1、已认证站长、可识别机器人 UA。机器人过滤只是启发式，不能证明全无机器人。
- 202 `{status:"queued"|"dropped"}` 仅确认有界队列接收/丢弃，**不承诺持久化**；204 表示关闭或排除。队列 256 个、单 worker、内核单次超时 1 秒；网络/5xx 以同一 payload 重试一次，仍失败则丢弃。内容业务从不等待内核请求。
- 校验 400 `VALIDATION_FAILED`；跨站 403 `ACCESS_DENIED`；缺 CSRF 403 `CSRF_INVALID`；同一会话每分钟最多 60 个事件，超限 429 `ANALYTICS_RATE_LIMITED`。

## 管理报表

管理员会话才能读取（无缓存）：

- `GET /api/v1/admin/analytics/status` → `{enabled,queued,delivered,dropped}`。计数只覆盖当前进程，delivered 包括内核确认的幂等重复；不是业务 PV 总数。
- `GET /api/v1/admin/analytics/report?from=2026-10-01&to=2026-10-09&path=&source=`：from 包含、to 不包含，UTC 日期，最多 366 天。path/source 可选，仅允许一次且不接受未知参数。200 透传 waymarks 的受限聚合报表，字段/定义以固定 v1 为准。非法参数 400；关闭 503 `ANALYTICS_DISABLED`；超时/内核认证或格式故障 503 `ANALYTICS_UNAVAILABLE`，均使用本站错误体。

界面显示 PV、未识别 PV、访客日（每日去重之和，不能称跨日独立访客）、近似访问（UTC 固定 30 分钟桶）、每日趋势、来源及热门路径。未提供真实 GeoIP 数据源，因此地区为 unknown，不宣称真实地区识别。

## 配置及保留

默认关闭：`RAYCHI_ANALYTICS_ENABLED=false`。开启还需 `RAYCHI_ANALYTICS_URL`（默认 http://127.0.0.1:8093）、`RAYCHI_ANALYTICS_APP`（默认 raychi-public）、`RAYCHI_ANALYTICS_TOKEN`（collect/read 对指定 app）、`RAYCHI_ANALYTICS_ORIGIN`（浏览器同源 origin）。内核默认原始事件 30 天、聚合及每日匿名标识 365 天；浏览器 sessionStorage 每个 UTC 日随机新标识，不跨天沿用，禁用存储仍可匿名 PV。无原始 IP 传递/存储，无第三方脚本。

独立 SQLite 不加入内容数据库。待发布安装配置需要独立数据目录、私有网络、持久访客 HMAC 密钥及备份覆盖；仅本地开发/验收不代表已启用线上采集。
