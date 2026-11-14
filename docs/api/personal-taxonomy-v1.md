# 个人设置与分类标签管理 v1

Wellspring 拥有账户、站点头像与分类标签目录；管理台通过同源会话及 CSRF 调用。Schema 见 [OpenAPI](openapi-v0.2.json)，沿用统一错误体与 requestId。旧设置、改密、内容保存/发布及公开目录接口保持兼容。

## 个人设置

- `POST /api/v1/auth/username`：`{currentPassword, username}`。新用户名 1–100 字符，不含空白/控制字符、不裁剪，不能与当前相同；当前密码验证及 UTF-8 72 字节上限沿用改密。成功 204，递增持久化凭据版本并注销当前会话；其他旧会话在下次请求失效。旧用户名不再登录，密码保持；重启不会被 bootstrap 环境覆盖。错误当前密码 400 `CURRENT_PASSWORD_INVALID`，非法字段 400 `VALIDATION_FAILED`，并发凭据变化 409 `CREDENTIALS_CHANGED`。
- `POST /api/v1/admin/profile/avatar`：multipart `file` 和当前站点设置 `version`。真实解码校验 PNG/JPEG、最多 2 MiB/1600 万像素；重新编码为 PNG 并缩到最长边 512px，保留比例、去除元数据。返回完整最新站点设置；与头像数据在同一事务内更新。旧版本 409 `SETTINGS_VERSION_CONFLICT`，非法图片 400 `ASSET_INVALID`，超出文件大小 413 `ASSET_TOO_LARGE`。不接受 SVG。
- `GET/HEAD /api/v1/public/avatar/{id}/content`：仅当前已保存头像，匿名可读，`image/png`、`no-store`、`nosniff`。替换后旧 ID 返回 404；失败或版本冲突不替换旧图。HEAD 保留长度，无响应体。
- 头像共用于管理台侧栏、写作对话及公开站，与网站设置中的 `avatarUrl` 为同一权威值。保存只修改头像，保留网站其他字段；旧客户端完整设置保存仍有原版本检查。当前头像单行保存在内容数据库并由现有数据库备份覆盖，不创建虚构文章或修改正文附件权限。
- 预览与取消仅管理台本地操作，选择文件不发送请求；明确保存才上传。改密仍使用 `/auth/password` 及既有规则。用户名和密码成功修改后均需重新登录。

认证相关写操作需要当前会话与 CSRF；匿名 401、缺失/错误 CSRF 403。密码不回显、不入日志或浏览器持久存储。

## 分类标签管理

`{kind}` 仅 `categories` 或 `tags`。以下均需要管理会话，写操作同时要求 CSRF。

| 接口 | 语义 |
| --- | --- |
| GET `/api/v1/admin/taxonomy/{kind}` | 目录项名称、按内容去重的工作稿/保存的发布版本/回收站/保留历史版本引用数量、跨范围去重总数及默认项保护标志 |
| GET `/api/v1/admin/taxonomy/{kind}/references?name=...` | 去重关联内容 `id/title/status`，包含回收站与仅被历史引用的内容 |
| PUT `/api/v1/admin/taxonomy/{kind}` | `{name,newName}`，只更名未被引用的项 |
| DELETE `/api/v1/admin/taxonomy/{kind}?name=...` | 只删除未被引用的项 |

创建继续复用 `/admin/categories` 与 `/admin/tags`；分类最多 80、标签最多 40 字符，裁剪名称首尾空白、拒绝控制字符，重名 409 `NAME_CONFLICT`。未知目录或名称 404 `TAXONOMY_NOT_FOUND`；默认“未分类”不可改删，409 `TAXONOMY_PROTECTED`；任何工作稿、保存的发布版本、回收站或保留历史引用阻止改删，409 `TAXONOMY_IN_USE`。大小写保持现有目录语义。

目录管理与内容创建/保存/历史恢复/发布统一先锁定永久“未分类”目录行，再进行引用检查或持久化；检查与改删在同一事务中，防止并发新增引用后删除目录。不会改写内容、公开快照、搜索索引或历史。回收站中的历史引用统一计入回收站列；同一内容在不同列可重复，总数跨列去重。发布版本包括撤回后仍保留的快照，并不等于当前公开篇数。

草稿中尚未发布的新标签仍不进入目录，沿用确认发布事务内创建的规则。历史中的分类也作为引用保留，以免历史恢复失去合法分类。关联内容为回收站状态时管理台进入回收站；其他状态进入内容编辑，可继续查看历史版本。

`AdminPersonalTaxonomyTest` 覆盖认证/CSRF、当前密码、失效旧会话、真实图片校验与版本原子性、引用去重/快照隔离，以及跨事务引用创建和删除的竞争。测试可按 README 使用独立 PostgreSQL 运行。

离线MySQL→PostgreSQL工具兼容V14（16表，无头像）和V15（17表，含头像）；新目标始终应用最新PG迁移，旧目标V1仍可对V14执行只读校验。头像使用原始二进制复制/指纹，校验不跳过新增表或源数据。
