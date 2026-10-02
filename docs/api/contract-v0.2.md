# Raychi 内容与站点接口 v0.2

日期：2026-09-28。对应[跨仓主 Issue #1](https://github.com/raychi-space/raychi/issues/1)及[内容服务 Issue #2](https://github.com/raychi-space/wellspring/issues/2)。机器可读定义见 [OpenAPI v0.2](openapi-v0.2.json)。本版与 [v0.1](contract-v0.1.md) 共存，旧长文地址和接口保留。

## 内容类型与地址

- 新内容只有 `ARTICLE`（文章）和 `POST`（帖子），对应 `/writing/{slug}`、`/posts/{slug}`；旧文章 `slug` 不变。
- 新建帖子时服务端生成稳定的 `post-{UUID}` slug，标题可空。文章标题必填，首次发布前须填写正式 slug。
- 文章的 `category` 必须是现有分类；新建时省略或空值归入 `未分类`，保存时省略字段保留现有分类，显式传空字符串归入 `未分类`。帖子 `category` 必须为空。文章和帖子的 `tags` 可为多个已建立的标签。分类/标签由站长通过创建接口新增；本版不提供更名或删除。
- 帖子的 Markdown 不得包含图片节点或 HTML `<img>`；封面与图片上传只属于文章。文章沿用 v0.1 的图片私密、快照引用和撤回规则。
- 保存工作稿只更新管理版本与 `updatedAt`，不更新 `publishedAt`、公开快照和公开顺序。首次发布确定 `publishedAt`；再次发布更新 `publicUpdatedAt`，保持 `publishedAt` 与原位置。撤回后公开列表和详情返回 404/不包含，重发保留原地址与首次发布时间。

## HTTP

公开 GET 均无需登录，只返回当前已发布快照：

| 路径 | 行为 |
| --- | --- |
| `/api/v1/public/contents?page=1&pageSize=12&type=&category=&tag=` | 混合或按类型列表；`type` 为上述枚举，分类/标签按名称精确筛选；按 `publishedAt DESC, id DESC` 稳定排序；`pageSize` 最大 50。列表含发布版正文，供短内容和回顾首句展示。 |
| `/api/v1/public/contents/{type}/{slug}` | 单条发布快照；草稿、撤回及不存在统一 404。 |
| `/api/v1/public/categories`、`/api/v1/public/tags` | 可选名称列表。 |
| `/api/v1/public/settings` | 当前站点展示配置，保存成功后立即可读。 |

管理端 GET/POST/PUT 都要求站长会话，写入要求当前会话 CSRF：

| 路径 | 行为 |
| --- | --- |
| `/api/v1/admin/contents?type=&status=&page=&pageSize=` | 管理列表；不传 `type` 为全部类型。 |
| `POST /api/v1/admin/contents?type=POST` | 新建指定类型的草稿；请求体可为空；返回管理详情与 `Location`。 |
| `GET/PUT /api/v1/admin/contents/{id}` | 读取、完整保存工作稿；PUT 需要 `version`，过期返回 409。 |
| `POST /api/v1/admin/contents/{id}/publish`、`.../unpublish` | 请求体 `{ "expectedVersion": N }`；明确发布或撤回，过期返回 409。 |
| `POST /api/v1/admin/categories`、`.../tags` | 请求体 `{ "name": "..." }`；重名返回 409。 |
| `GET/PUT /api/v1/admin/settings` | 读取或立即保存整份配置；PUT 携带 `version`，过期返回 409。 |

`ContentInput` 传 `slug`、`title`、`summary`、`bodyMarkdown`、`tags`、`coverUrl`、`category` 和 `version`。管理详情另有不可变 `id/type`、`status`、`hasUnpublishedChanges` 与时间戳。公开详情与列表仅取发布快照。旧 `THOUGHT` 记录在新列表和详情中按 `POST` 返回，并进入帖子时间线；新建 `THOUGHT` 返回 400。旧草稿保存或发布时转换为 `POST`，保留 ID、slug、正文和版本；旧 `/thoughts/{slug}` 页面转向 `/posts/{slug}`。错误沿用 v0.1 的 `code/message/requestId/fieldErrors` 结构；新增 `NAME_CONFLICT` 与 `SETTINGS_VERSION_CONFLICT`。

## 站点配置

`siteName`、`intro`、可空 `avatarUrl`、`contacts`、`accounts`、`navigation`、`homeSections`、`homepage`、`projectIntro` 和 `socialAccounts` 是一份独立配置。前三种链接列表的元素为 `{label, href}`；链接只允许站内 `/路径`、HTTP(S) 或 `mailto:`。旧版 `homeSections` 固定为 `feed`、`writing`、`posts`、`thoughts` 四个，保留以兼容旧客户端。新首页使用 `homepage`：`focus` 为个人方向，`projects` 为最多三个 `{name, description, status, href}` 项目；`recentSections` 固定包含 `featured`、`posts`、`writing`，`bottomSections` 固定包含 `projects`、`stats`，各区块恰好出现一次，数组顺序决定展示顺序，`visible` 决定是否显示。项目说明由 `projectIntro` 保存。项目链接只允许站内路径或 HTTP(S)。`socialAccounts` 保存最多八个 `{platform, enabled, href}`，平台为 `github`、`x`、`bilibili`、`youtube`、`zhihu`、`juejin`、`xiaohongshu`、`mastodon`；开启的平台须填写 HTTP(S) 链接。旧 `accounts` 中同名平台会在读取时转换为图标账户，保存新配置后写入 `socialAccounts`。旧客户端保存时省略新增字段会保留当前配置。公开导航读取数据库中配置的首页、帖子、文章和回顾标签；V5 把旧默认“长文”改为“文章”。文章、帖子数字仍根据已发布内容自动计算。配置没有工作稿/发布步骤；保存不读取或改动内容工作稿。头像使用站长指定的安全 URL，本版没有头像上传接口。

## 数据迁移和兼容

Flyway V2 在原 `articles` 表增加类型与分类字段，不改变原 ID、slug、正文、发布快照或附件关联。既有记录是 `ARTICLE`，分类回填 `未分类`；V3 先将标签目录设为区分大小写的排序规则，再把旧工作稿和公开快照中的标签回填到目录，保留 v0.1 中如 `Tech` 与 `tech` 这样的不同标签。V4 为已成功执行早期候选版 V3 的数据库补齐同一排序规则。因此旧标签仍能在管理台选择、取消和重新添加，新增标签名仍须先创建。新表保存分类、标签目录和站点配置。V5 为旧站点配置补上首页默认资料，并把项目说明与平台账户列表写入数据库；已有首页项目和介绍会保留。v0.1 的 `/public/articles` 与 `/admin/articles` 仅处理文章；向旧管理文章详情或写入接口提供帖子、旧思考 ID 返回 404，旧前端仍可使用；旧公开文章列表仍不返回正文。迁移前需备份 MySQL；若早期候选版 V3 已失败，应从迁移前备份恢复，再使用当前版本重试。回滚也采用备份恢复，不删除 V2–V5 数据以避免丢失新内容。

## 响应与错误统一（2026-10-02）

成功 JSON 使用本版本已有 DTO 或分页结构；创建返回 201 和原有 Location，登出返回 204，图片保持二进制响应。响应均携带服务端生成的 `X-Request-Id`（UUID）。数据库 Entity、存储 JSON、草稿/发布字段集合不直接作为 HTTP 响应。

错误统一为 `code/message/requestId/fieldErrors`；`requestId` 与响应头一致，字段验证错误项为 `{field, message}`，无字段详情时返回空数组。业务错误与认证/CSRF 保留已有状态码和错误码。Malformed JSON、无法转换的参数、缺少必需参数或 multipart 字段返回 400 `VALIDATION_FAILED`；不支持的方法返回 405 `METHOD_NOT_ALLOWED` 并保留 Allow；不支持的请求媒体类型返回 415 `MEDIA_TYPE_NOT_SUPPORTED`；不支持的响应媒体类型返回 406 `MEDIA_TYPE_NOT_ACCEPTABLE`；未知资源返回 404 `RESOURCE_NOT_FOUND`。未归类的数据约束冲突返回 409 `DATA_CONFLICT`；未预期异常返回 500 `INTERNAL_ERROR`，详细异常仅记录到服务日志。

此次分层重构使用现有表结构及迁移版本；前端按原契约解析成功响应。
