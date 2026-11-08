# 站点访问统计 v2 扩展

站点 URL 继续使用 `/api/v1`。新增报表/明细消费 waymarks `/v2`；旧 `/admin/analytics/report` 仍返回旧聚合契约。升级顺序为 waymarks → wellspring → lantern/inkwell，最后更新部署源锁与模板；开发不自动部署。用户已明确确认全公开页、原始 IP、离线归属地与管理台新布局，主任务 raychi#76。

## 采集

`POST /api/v1/public/analytics/events` 仍仅接受 `{id,path,occurredAt,visitorId?,source?}`。保留 Cookie/CSRF、精确 Origin、DNT/GPC、已登录站长和可识别机器人排除、每会话每分钟 60 次以及有界异步队列。默认管理偏好为开启（V14），仍须有效的服务配置；关闭后不删除历史，也不补录关闭期间访问。

path 扩展为 512 字符以内的公开页面路径，覆盖拾阅、搜索、404、插画等，查询串/片段不进入事件。拒绝控制字符、反斜线、协议相对地址、路径穿越，以及管理台、API、Next 静态资源和 `.well-known` 前缀（含编码变体）。不再用“已发布详情存在”作为采集前提，因为 404 本身也在采集范围。浏览器全局导航监听只发一次，SSR/预取/API/图片不是 PV。

IP 不接受浏览器字段；桥接层从 Servlet `getRemoteAddr()` 获得地址并规范化文字 IP，不进行域名解析。生产入口 Nginx 必须覆盖客户端传来的 `X-Forwarded-For` / `X-Real-IP` / `Forwarded`，服务端口保持私网/loopback。只有经过可信入口的转发策略才能启用；不能把任意外部转发头当作可信 IP。向内核 v2 发送 IP 后，明细保留 30 天；浏览器随机标识改为本域 localStorage 稳定 UUID，受存储限制时仅计 PV。原始访客 ID 不存储，跨日去重用内核私有 HMAC。

## ADMIN 管理查询

新增 `GET /api/v1/admin/analytics/overview` 与 `GET /api/v1/admin/analytics/events`，均需要 ADMIN 会话，响应 no-store。访客 401、已认证非 ADMIN 403。请求参数和响应形状以本站 OpenAPI 的 `AnalyticsOverview` / `AnalyticsDetailsPage` 为准；筛选为 `from,to,path,source,ip,country,province,city`，明细另支持 `page,pageSize`。日期在北京时间按包含 from、不包含 to，最多 366 天；界面将含当天的结束日转换为排他上界。

未知、重复或不合法参数 400 `VALIDATION_FAILED`。未配置 503 `ANALYTICS_DISABLED`；内核错误、超时、响应不符合契约或时区并非 `Asia/Shanghai`，返回 503 `ANALYTICS_UNAVAILABLE`，不能显示成零流量。桥接层白名单裁剪响应，详情不暴露匿名访客键、token 或其他内核字段。关闭采集不影响这些只读接口。

四卡为全站累计 PV、累计识别访客、今日 PV、今日识别访客；筛选作用于图表和列表。每个浏览器标识近似一个访客，不能解释成精确人数；历史访客日不能相加成为累计访客。新增访客口径从内核标明的起始时间生效。总 PV 保留可用历史，旧 IP/归属地无法补录。原始明细/IP 保留 30 天，日聚合 365 天；累计计数和无 IP 的累计匿名身份集继续保留。
