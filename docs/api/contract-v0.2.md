# Raychi 内容与站点接口 v0.2

日期：2026-09-28。对应[跨仓主 Issue #1](https://github.com/raychi-space/raychi/issues/1)及[内容服务 Issue #2](https://github.com/raychi-space/wellspring/issues/2)。机器可读定义见 [OpenAPI v0.2](openapi-v0.2.json)。本版与 [v0.1](contract-v0.1.md) 共存，旧长文地址和接口保留。

## 内容类型与地址

- 新内容只有 `ARTICLE`（文章）和 `POST`（帖子），对应 `/writing/{slug}`、`/posts/{slug}`；旧文章 `slug` 不变。
- 新建帖子时服务端生成稳定的随机 UUID slug，标题可空。文章标题必填，首次发布前须填写正式 slug。
- 文章的 `category` 必须是现有分类；新建时省略或空值归入 `未分类`，保存时省略字段保留现有分类，显式传空字符串归入 `未分类`。帖子 `category` 必须为空。文章和帖子的 `tags` 可为多个已有或尚未建立的标签名称；缺失标签在成功发布事务内建立。分类由站长通过创建接口新增，标签管理接口仍可显式新增；本版不提供更名或删除。
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

Flyway V2 在原 `articles` 表增加类型与分类字段，不改变原 ID、slug、正文、发布快照或附件关联。既有记录是 `ARTICLE`，分类回填 `未分类`；V3 先将标签目录设为区分大小写的排序规则，再把旧工作稿和公开快照中的标签回填到目录，保留 v0.1 中如 `Tech` 与 `tech` 这样的不同标签。V4 为已成功执行早期候选版 V3 的数据库补齐同一排序规则。因此旧标签仍能在管理台选择、取消和重新添加，新标签名可以只保存在工作稿中，发布时再加入标签目录。新表保存分类、标签目录和站点配置。V5 为旧站点配置补上首页默认资料，并把项目说明与平台账户列表写入数据库；已有首页项目和介绍会保留。v0.1 的 `/public/articles` 与 `/admin/articles` 仅处理文章；向旧管理文章详情或写入接口提供帖子、旧思考 ID 返回 404，旧前端仍可使用；旧公开文章列表仍不返回正文。迁移前需备份 MySQL；若早期候选版 V3 已失败，应从迁移前备份恢复，再使用当前版本重试。回滚也采用备份恢复，不删除 V2–V5 数据以避免丢失新内容。

## 响应与错误统一（2026-10-02）

成功 JSON 使用本版本已有 DTO 或分页结构；创建返回 201 和原有 Location，登出返回 204，图片保持二进制响应。响应均携带服务端生成的 `X-Request-Id`（UUID）。数据库 Entity、存储 JSON、草稿/发布字段集合不直接作为 HTTP 响应。

错误统一为 `code/message/requestId/fieldErrors`；`requestId` 与响应头一致，字段验证错误项为 `{field, message}`，无字段详情时返回空数组。业务错误与认证/CSRF 保留已有状态码和错误码。Malformed JSON、无法转换的参数、缺少必需参数或 multipart 字段返回 400 `VALIDATION_FAILED`；不支持的方法返回 405 `METHOD_NOT_ALLOWED` 并保留 Allow；不支持的请求媒体类型返回 415 `MEDIA_TYPE_NOT_SUPPORTED`；不支持的响应媒体类型返回 406 `MEDIA_TYPE_NOT_ACCEPTABLE`；未知资源返回 404 `RESOURCE_NOT_FOUND`。未归类的数据约束冲突返回 409 `DATA_CONFLICT`；未预期异常返回 500 `INTERNAL_ERROR`，详细异常仅记录到服务日志。

此次分层重构使用现有表结构及迁移版本；前端按原契约解析成功响应。


## 发布后摘要与编辑元数据（2026-10-02）

文章创建/保存时从正文首个**顶层 Markdown 标题**（任意标题级别、包括 Setext）提取纯文字标题；代码块与引用中的标题不计，链接/格式去掉标记。最多200字符。没有标题时兼容旧客户端提交的 title 和已有文章；管理台新写作流程在正文内写标题。

新文章不传 slug 时生成 `YYYY-MM-DD-<唯一后缀>`（日期按 Asia/Shanghai）；新帖子使用随机 UUID 作为地址别名，不从正文、标题或摘要推导。首次发布后不变。旧链接不迁移；未发布的旧 `draft-*` 别名在保存时转为日期别名。显式传入的旧自定义地址仍兼容。

发布请求保留 expectedVersion，新增可选 assistantId（仅文章生效）。发布事务同时保存 V7 `publication_summaries` 的最新任务；不在事务中调用模型。省略助手时后台选择首个已启用助手；无助手为 SKIPPED，有效配置/模型不可用为 FAILED，发布本身照常成功。编辑、停顿和保存草稿不会启动摘要。

管理内容响应新增 summaryStatus：NONE/PENDING/RUNNING/SUCCEEDED/FAILED/SKIPPED/CANCELLED，及 nullable summaryError 安全错误码。后台通过现有受控写作适配层使用已发布全文；任务 ID 与助手选择持久化，创建使用固定 jobId 幂等键，关闭页面/重启 API 后继续查询内核任务；未记录正文副本或密钥。模型结果需非空且不超过600字符，执行最长150秒。失败保留原摘要，再次发布创建新任务重试。

结果写入公开摘要；只有工作稿正文、标题与旧摘要仍与发布快照相同时才同步工作稿摘要，不把私人改动带到公开内容。完成更新文章 version（updatedAt/publicUpdatedAt 保持内容保存/发布时间口径）并同步搜索投影，客户端需使用最新 version 保存。只对同一 jobId、同一 publicUpdatedAt 且仍 PUBLISHED 的快照应用；重新发布替换任务，撤回取消，删除清理。没有把 Agent 核心接口加入文章专属字段。

分类标签目录接口不变。写作界面的新标签先保存在本地或工作稿，输入、回车及保存均不创建目录记录。发布事务负责创建缺失标签、复用已有名称并发布快照，任一步失败全部回滚；取消预览也不创建标签。标签保持大小写区分、去重、最多20个、每个最多40字符且禁止控制字符。显式标签管理 POST 仍可主动创建目录项。分类创建逻辑不变。

## 数据库分页与归档排序（2026-10-03）

`GET /api/v1/public/contents` 新增可选 `sort=latest|oldest|title`，默认 latest。排序先于分页，日期并列按 id 稳定排序；title 使用公开标题，无标题时使用公开正文，字符串顺序由数据库排序规则决定。未知排序返回 400 VALIDATION_FAILED。page/pageSize/total 与原响应兼容。

标签筛选使用发布时同步的大小写敏感索引；保存工作稿不改变该索引。V8 从已有 public_tags 回填索引；V9 增加附件删除队列。删除内容事务提交后后台回收文件，失败保留队列重试，回滚不删除文件。不会扫描或删除旧目录中的未知文件。

管理列表新增 category、tag、sort（recent/oldest/type/category/tag），筛选和排序先于数据库分页。标签匹配工作稿中的完整标签且区分大小写；MySQL 使用 JSON_CONTAINS。管理台每次只加载一页，筛选重置页码；字数与类型统计明确标为本页统计。

## 管理员修改密码

`POST /api/v1/auth/password` 需要登录会话与 CSRF，JSON 为 `{currentPassword, newPassword}`。新密码至少 12 个 Unicode 字符，UTF-8 最多 72 字节，不能全为空白或与当前密码相同；不裁剪首尾空格。当前密码最多 72 字节。成功返回 204 空响应，当前会话立即注销，其他既有会话在下一次请求时按持久化凭据版本注销；旧会话读取 `/auth/session` 返回 `authenticated:false`，管理 GET 返回 401。已执行中的请求不会回滚；写作 SSE 连接在每次任务读取前核验凭证版本，退出或改密后停止转发。

错误采用统一格式：400 `CURRENT_PASSWORD_INVALID`（旧密码错误，保留当前会话）；400 `VALIDATION_FAILED`（长度/空值/相同密码）；401 `AUTH_REQUIRED`；403 `CSRF_INVALID`；409 `PASSWORD_CHANGED`（并发修改，要求重新登录）。确认密码仅由管理台校验，不传到服务端。

V10 增加 MySQL `admin_account` 单管理员记录，存 BCrypt 哈希与递增凭据版本。首次空表使用环境变量初始化；已有账号时忽略初始化凭据，重启不能覆盖网页修改。修改后不返回或记录明文/哈希。环境变量不再充当密码重置入口，账号记录应随 MySQL 一起备份。认证仍使用 Spring Security，会话不改为浏览器持有密码。

## 发布前元数据（2026-10-03）

新增写作 mode `metadata`，仍使用站长会话、CSRF、任务所有者隔离和现有 Agent 资源限制。context 提供 title 与 documentMarkdown；工具要求 title（1–200）、summary（1–600）、englishTitle（1–300）。成功 proposal.kind 为 metadata，同时返回 slug：英文标题小写、按非字母数字分词，取前五词用连字符连接（最长120）。无有效英文单词或超长结果拒绝。任务本身不保存或发布内容。

管理内容 PUT 可传 publicationMetadata=true，保留显式发布标题、摘要、别名；版本和别名唯一约束照常生效，首次发布后别名不可修改。发布 POST 可传 metadataReviewed=true，直接发布已确认工作稿，取消旧摘要任务且不再排队覆盖摘要。缺省 false 保留旧客户端兼容行为。界面先保存工作稿，再生成并保存元数据；仅确认发布才公开，模型失败保留工作稿，用户可重试。前端会等待任务完成，关闭浏览器可能中断元数据的回写，但已保存正文不会丢失。

访问统计的匿名采集与管理员聚合报表是兼容扩展，见[统计 v1](analytics-v1.md)，不改变内容快照或认证字段。

公开文章的可选相关推荐是兼容只读扩展，见[相关文章 v1](related-articles-v1.md)，仅返回发布元数据，不改变内容保存或发布状态。

管理员单篇内容离线包见 [内容导出v1](content-export-v1.md)，兼容只读扩展，不包含外部讨论或未保存编辑。
