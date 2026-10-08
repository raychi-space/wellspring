# 内容导出 v1（管理员离线包）

主任务 raychi#58，后端任务 #32，消费方 inkwell。兼容只读扩展，无迁移或模型调用。

`GET /api/v1/admin/contents/{id}/export?expectedVersion={version}`；管理员认证，无CSRF要求。expectedVersion必填非负整数；缺失/非法400 `VALIDATION_FAILED`，内容不存在404 `ARTICLE_NOT_FOUND`，已保存版本不同409 `VERSION_CONFLICT`。成功200 `application/zip`，`Cache-Control: no-store`、`X-Content-Type-Options: nosniff`、`X-Request-Id`，`Content-Disposition: attachment; filename="raychi-{id}-v{version}.zip"`。仅ARTICLE/POST（旧THOUGHT按POST）自己的内容与附件，不提供公开导出。

包包含 `content.json`（schemaVersion1、id/slug/type/status/version、创建/编辑/首次发布/最近发布日期，工作稿和最近发布快照，所属附件映射/尺寸/类型/字节/SHA256）、`draft.md`、有历史发布时的 `published.md`、`assets/{UUID}.png|jpg` 和 `manifest.json`（schemaVersion1、每个其他文件的相对路径、字节数和SHA256）。JSON快照保留原始Markdown、标签、分类、封面及地址，Markdown文件中所属图片的本站标准地址替换为包内相对路径。附件包括该内容上传的未引用图片，不读其他内容附件或外部URL。

未首次发布时published为null且无published.md；撤回时仍导出最近发布记录，但不改变公开状态。包包含私密工作稿，不含账户/会话/模型密钥、站点配置、统计或GitHub讨论。导出用于离线留存，没有自动导入/恢复接口，不替代完整数据库与附件备份。页面未保存的编辑不包含在内，管理台要求先保存并按当前版本导出。

以锁定的内容行和一致性SQL读取生成完整包，限制最多256附件和64MiB解压后文件总字节（不计清单）；越界413 `CONTENT_EXPORT_TOO_LARGE`。附件缺失/损坏或生成失败500 `CONTENT_EXPORT_FAILED`，使用统一JSON错误且不返回部分ZIP；只用固定UUID包内路径，不输出存储键或服务器文件路径。生成包后才设置下载响应，导出不修改版本、状态、搜索或内容。
