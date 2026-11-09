# 拾阅 AIHOT 接入

AIHOT拥有采集、原始材料、模型分析、事件归并和汇总；Wellspring只通过其固定版本的公开v1 API读取，映射站点DTO，不连接AIHOT数据库、不承担新的模型循环。浏览器只访问本站接口。AIHOT模型配置独立于站点Agent配置，保留现有Agent主密钥。

## 公开接口

`GET /api/v1/public/reading/discovery`，无需登录，响应no-store：

- `view=hot`（默认）：热榜。最多20个事件，按AIHOT排序，补充公开事件摘要与原文来源。
- `view=latest&cursor=...`：最近7天资讯，使用AIHOT all模式，每页30条；`nextCursor`为null表示结束，不自行计算offset。
- `view=reports&period=daily|weekly|monthly&key=latest|期数`：默认日刊/最新一期。期数分别为真实日期`YYYY-MM-DD`、有效ISO周`YYYY-Www`、月份`YYYY-MM`；`issues`提供最近30期，亦可直接指定历史期数。

hot只接受view，latest只接受view/cursor，reports只接受view/period/key；未知或不适用参数、无效期数/游标返回400/VALIDATION_FAILED。上游失效游标也是400，不伪装为空列表。具体字段以同目录OpenAPI的DiscoveryPage/Entry/Report为准。

`mode=legacy`表示未启用AIHOT，调用方沿用原`GET /public/reading`；`mode=aihot`使用新页面。原接口形状保持，旧数据不删除。新增接口先于消费方部署，旧后端404也允许消费方继续旧页面。

所有文本按纯文本展示；不转发HTML、内部站点地址、模型请求、上游额外字段或凭据。原文只接受无用户信息的HTTP(S)公开主机链接，禁止javascript及明显私网地址，不在站点服务端抓取该链接。卡片展开多个来源，跳转原文；不搬运全文或外部图片。

无汇总返回`report:null`，空资讯返回空数组，`available:true`。上游不可用时可使用至多15分钟前的同查询成功缓存并标记`stale:true`，没有可用缓存则`available:false`且数组为空，不影响其他页面；缓存每查询60秒、至多64个查询，仅进程内保留。总读取截止4秒、单次1.8秒、每响应1MB；热点摘要可选降级，不让批量补充无限阻塞。事件合并只接受同一服务内v1 stories路径的有限308跳转，不跟随任意重定向。

## 管理与切换

`GET /api/v1/admin/reading/integration`需要登录，返回mode/configured/connected/legacyActive与checkedAt/lastSuccessAt/updatedAt，不暴露配置地址或密钥。刷新仅重新检查连接，不触发抓取或模型调用。

`RAYCHI_READING_AIHOT_ENABLED=true`时，旧两个抓取/翻译Worker不注册；旧写入/同步接口返回409/READING_MANAGED_EXTERNALLY（仍保留登录及CSRF校验），旧只读接口可用，所有旧来源/文章保留。禁用该选项可恢复旧方式，不能通过删除历史数据完成切换。

私有配置仅需`RAYCHI_READING_AIHOT_ENABLED`和`RAYCHI_READING_AIHOT_URL`，默认关闭、URL默认为本机18071；dev配置使用Docker内`http://aihot-api:3001`。域名、模型、源、预算与AIHOT库配置归独立运行部署，不硬编码dev或正式域名到站点代码。
