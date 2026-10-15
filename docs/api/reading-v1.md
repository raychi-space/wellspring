# 拾阅 API v1

拾阅收集指定博客列表页的文章元数据，公开站按来源展示并跳转到原网站。Wellspring 只保存来源、原标题、中文译题、原始链接和发布日期，不下载正文、摘要或外部图片。

## 公开读取

`GET /api/v1/public/reading` 无需登录，返回固定 90 天窗口内的启用来源。每个来源包含按发布日期倒序排列的文章；`translatedTitle` 在翻译尚未完成时省略，客户端使用 `originalTitle` 回退。`illustrationSeed` 是由标题生成的稳定种子，不是图片地址。

## 管理接口

- `GET /api/v1/admin/reading` 返回设置、来源、检查状态和文章数量。
- `PUT /api/v1/admin/reading/settings` 使用 `version` 乐观锁保存 `translationAssistantId`；空值暂停新的翻译任务。
- `POST /api/v1/admin/reading/sources` 新增来源。
- `PUT /api/v1/admin/reading/sources/{id}` 更新名称、说明、地址、适配器、启用状态和排序。
- `DELETE /api/v1/admin/reading/sources/{id}` 删除来源及其链接。
- `POST /api/v1/admin/reading/sources/{id}/sync` 立即检查一个来源。

管理接口需要 ADMIN 会话；写操作需要 CSRF。v1 适配器只允许 `OPENAI_DEVELOPERS` 与 `ANTHROPIC_ENGINEERING`，并限制到对应官方 HTTPS 主机，避免管理配置成为任意网络请求入口。

## 同步与翻译

后台检查周期由 `RAYCHI_READING_SYNC_MS` 配置，默认 14400000 毫秒（4 小时）。每个来源独立记录最后检查、最后成功和安全错误码；失败保留上次成功结果并在后续周期重试。

新链接立即以原标题进入公开结果。配置翻译 Assistant 后，独立队列通过 `TranslationService` 创建 agent-core 配置化 run，成功后写入中文译题。标题变化会清除旧译题并重新排队；失败按退避时间重试。Assistant ID 由站点设置绑定，不写死在代码中。推荐使用 agent-core 的 `general-translation-zh-cn` 模板注册专用的“通用翻译助手”。
