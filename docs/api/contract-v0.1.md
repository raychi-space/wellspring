# Raychi 最小 API 契约 v0.1

日期：2026-09-28。状态：已实现文章、身份和图片第一版；机器可读定义见 [OpenAPI v0.1](openapi-v0.1.json)。本文件保留产品语义和示例，接口以实现与 OpenAPI 一致性为准。

已确认：三个独立仓库；管理台和数据库组成新系统；保存已发布文章的修改不影响公开版本，再发布才更新；管理台同一区域所见即所得编辑，仍以 Markdown 保存；第一版支持粘贴图片上传并就地显示，未发布图片保持私密。其余字段细节为实现建议。

图片接口、权限和状态表见[图片上传与发布契约](assets-v0.1.md)，属于本契约的第一阶段范围。

## 1. 领域规则

文章拥有不可变 `id`，传输格式建议为 UUID 字符串。`slug` 决定公开路径 `/writing/{slug}`，全库唯一。建议使用小写字母、数字、单连字符；长度 1–120，首尾不能为连字符。首次发布后锁定 slug，包括撤回期间，后续增加改址与重定向时再放开。

概念上区分以下数据，SQL 表结构在接口定稿后确定：

| 数据 | 内容 | 可见性 |
| --- | --- | --- |
| Article | id、slug、status、version、创建/修改/首次发布时间 | 管理接口完整读取；公开接口仅返回所需字段 |
| WorkingCopy | title、summary、bodyMarkdown、tags、coverUrl | 站长可编辑 |
| PublishedSnapshot | 上次明确发布的全部展示字段及发布时间信息 | 文章处于 PUBLISHED 时对访客可读 |
| Asset / 图片引用集合 | 私有存储元数据、所属文章、工作稿与发布快照分别引用的图片 ID | 站长可预览；仅当前发布快照引用的图片公开可读 |

只要求一份工作稿和一份最新发布快照；完整历史版本、定时发布、协作编辑不进入本轮。

- `status` 为 `DRAFT` 或 `PUBLISHED`，表达当前是否公开。
- `hasUnpublishedChanges` 表达工作稿与最近发布快照是否有差异；没有发布快照时为 true。
- `version` 是文章管理侧乐观锁版本，成功修改文章时递增；独立上传图片不修改文章版本。多标签页保存过期版本返回 409，不能静默覆盖。
- `createdAt` 为首次创建时间；`updatedAt` 为管理记录最近变更时间；`publishedAt` 为首次发布时间；`publicUpdatedAt` 为最近一次发布快照更新时间。公开接口不暴露草稿修改时间。
- 时间通过带时区的 ISO 8601 字符串传输，建议 API 统一 UTC，界面按 Asia/Shanghai 展示。导入历史日期的具体规则后续定义。
- 新建/保存工作稿允许标题和正文暂空；创建时可省略编辑字段，后端生成草稿 ID 和唯一临时 slug，以便首次粘贴时绑定图片。发布时必须有有效标题、slug 和正文。长度上限及字段校验需在 OpenAPI 中定稿。
- 标签和封面是可选展示字段；第一版实现持久图片上传与读取权限，不建立完整媒体库。若封面引用本站托管图片，适用与正文相同的权限规则。
- 发布在一个数据库事务内复制所有工作稿展示字段及其图片引用集合、设置状态和时间、更新版本。
- 撤回取消文章及其图片的新公开读取权限，保留编辑内容、文件和内部 ID；公开详情及图片不可见时均与不存在统一返回 404。

## 2. HTTP 边界

公开端点 `/api/v1/public/*` 不需要登录，也不因站长 Cookie 而返回工作稿。管理端点 `/api/v1/admin/*` 均需要站长会话，写操作需要 CSRF。

| 方法 | 路径 | 行为与成功响应 |
| --- | --- | --- |
| GET | `/api/v1/auth/csrf` | 200，返回当前 CSRF token 和请求头名称；允许登录前调用 |
| POST | `/api/v1/auth/login` | 校验 username/password 和 CSRF，200 返回站长会话信息并设置会话 Cookie |
| GET | `/api/v1/auth/session` | 200，返回是否登录；未登录为 `authenticated: false` |
| POST | `/api/v1/auth/logout` | 校验 CSRF，失效会话，204 |
| GET | `/api/v1/public/articles` | 200，已发布摘要列表和分页信息 |
| GET | `/api/v1/public/articles/{slug}` | 200，发布快照详情；不可见/不存在均 404 |
| GET | `/api/v1/admin/articles` | 200，管理列表，可按 DRAFT/PUBLISHED 筛选，包含待发布修改标记 |
| POST | `/api/v1/admin/articles` | 201，新建工作稿，返回管理详情及 Location |
| GET | `/api/v1/admin/articles/{id}` | 200，管理详情：工作稿、状态、version、发布时间等 |
| PUT | `/api/v1/admin/articles/{id}` | 200，完整保存工作稿，校验 version，不修改发布快照 |
| POST | `/api/v1/admin/articles/{id}/publish` | 200，校验 expectedVersion，发布当前已保存工作稿，返回管理详情 |
| POST | `/api/v1/admin/articles/{id}/unpublish` | 200，校验 expectedVersion，撤回并返回管理详情 |
| POST | `/api/v1/admin/articles/{id}/assets` | 201，会话＋CSRF，上传图片并返回稳定地址，默认不公开 |
| GET / HEAD | `/api/v1/admin/assets/{assetId}/content` | 200，站长会话下读取图片，供管理台预览 |
| GET / HEAD | `/api/v1/public/assets/{assetId}/content` | 200，仅当前发布快照中的图片可读；否则 404 |

永久删除、回收站、导出、导入和全页预览端点不进入此最小契约。上传和图片权限已纳入第一版；管理台内实时预览不需要额外的公开站草稿预览端点。

分页建议使用 `page`（从 1 开始）、`pageSize`（默认 10，最大 50），响应为 `items/page/pageSize/total`。公开列表按 `publishedAt DESC, id DESC` 稳定排序，管理列表按 `updatedAt DESC, id DESC`。公开列表返回发布快照的标题、摘要、标签、封面及公开时间，不返回工作稿或完整正文。

使用安全会话 Cookie，生产启用 HttpOnly/Secure 并设置适合部署的 SameSite 策略；不返回供前端长期保存的 JWT。登录成功后更新会话并重新获取 CSRF token。Spring Security 过滤器产生的 401/403 也必须使用下方统一错误格式，API 不跳转 HTML 登录页。

## 3. 请求示例

新建工作稿：

```http
POST /api/v1/admin/articles
Content-Type: application/json
X-CSRF-TOKEN: <当前 token>
```

```json
{
  "slug": "hello-raychi",
  "title": "第一篇长笺",
  "summary": "用一篇文章验证完整发布流程。",
  "bodyMarkdown": "# 你好\n\n这是我的个人空间。",
  "tags": ["随记"],
  "coverUrl": null
}
```

保存工作稿：

```http
PUT /api/v1/admin/articles/11111111-1111-4111-8111-111111111111
```

```json
{
  "version": 3,
  "slug": "hello-raychi",
  "title": "修改后的第一篇长笺",
  "summary": "这段修改还没有发布。",
  "bodyMarkdown": "# 你好\n\n这里是待发布的新正文。",
  "tags": ["随记"],
  "coverUrl": null
}
```

响应得到新版本（例如 4）后，只有明确发布才使改动公开：

```http
POST /api/v1/admin/articles/11111111-1111-4111-8111-111111111111/publish
```

```json
{ "expectedVersion": 4 }
```

所有管理请求都携带会话，所有写请求都携带当前 CSRF token，上述后两个示例省略重复头部。管理台有未保存输入时，“发布”应先保存成功，再使用返回的新 version 发布；保存失败不能接着发布旧数据。

公开详情响应示例（独立 DTO，不是管理详情删几个字段）：

```json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "slug": "hello-raychi",
  "title": "第一篇长笺",
  "summary": "用一篇文章验证完整发布流程。",
  "bodyMarkdown": "# 你好\n\n这是我的个人空间。",
  "tags": ["随记"],
  "coverUrl": null,
  "publishedAt": "2026-09-28T02:00:00Z",
  "publicUpdatedAt": "2026-09-28T02:00:00Z"
}
```

这是发布第一版后、第二版仍仅保存为工作稿时应返回的旧内容。

## 4. 错误与渲染

统一错误响应示例：

```json
{
  "code": "ARTICLE_VERSION_CONFLICT",
  "message": "文章已被更新，请刷新后重试。",
  "requestId": "req-example",
  "fieldErrors": []
}
```

| HTTP | code 示例 | 场景 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED | 字段或发布前置条件不满足 |
| 400 | ASSET_INVALID / ASSET_REFERENCE_INVALID | 图片内容无效或引用不属于当前文章/未就绪 |
| 401 | AUTH_REQUIRED / INVALID_CREDENTIALS | 无会话或登录失败 |
| 403 | CSRF_INVALID | CSRF 无效或缺失 |
| 404 | ARTICLE_NOT_FOUND | 不存在；公开接口也用于未发布/撤回 |
| 404 | ASSET_NOT_FOUND | 图片不存在；公开接口也用于私密或已撤回图片 |
| 409 | SLUG_CONFLICT / ARTICLE_VERSION_CONFLICT | slug 重复或过期写入 |
| 413 | ASSET_TOO_LARGE | 上传大小超限 |
| 400 | ASSET_INVALID | 图片格式无效或不支持 PNG/JPEG 之外的格式 |
| 500 | INTERNAL_ERROR | 未预期错误；不暴露异常堆栈 |

后端保存 `bodyMarkdown`，管理台把所见即所得编辑器文档序列化为 Markdown 提交；编辑器内部 JSON 和显示层 HTML 不作为正式正文。公开站展示前按统一规则安全渲染。初版禁用可执行原始 HTML/MDX，统一处理危险链接协议；规则和样例的最终版本同时约束两个前端。

编辑器往返转换允许等价语法归一化，但不能丢失内容结构、代码文本或图片地址。不支持的格式应保留原文并阻止破坏性覆盖，不能静默删掉后保存。上传占位、blob URL 和认证预览地址不能写入 `bodyMarkdown`；图片节点序列化使用稳定公开地址。具体交互要求见[编辑器约定](https://github.com/raychi-space/inkwell/blob/main/docs/editor-v0.1.md)。

## 5. 后续完善清单

- 明确全部请求/响应 schema、字段上限、null/省略语义和错误码，不只记录路径。
- 为已确认的上传和私密预览补齐 multipart、图片响应、授权规则与限制；永久删除保持后续范围。
- 用保存→公开旧版→再次发布→公开新版的实例评审前后端理解是否一致。
- 对图片同时验证首次发布前、已发布文章新增图片、更新发布移除旧图与撤回后的直链权限。
- 结合集成测试持续核对 OpenAPI 与实际响应；前端绑定一个契约版本，变更时重新生成或更新客户端。
- 上线前增加登录失败限流、可信反向代理配置和备份恢复演练。
