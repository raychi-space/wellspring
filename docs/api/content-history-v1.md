# 工作稿修订历史 v1

主任务 raychi#60，后端 #34，消费方 inkwell。新增V11；仅管理员可读，响应no-store/统一错误/X-Request-Id。保留每篇最近100条；既有内容没有虚构历史，首次新变更前录基线。创建/保存/明确发布/撤回/成功自动摘要/历史恢复同事务记录工作稿快照，失败回滚不记录；查询不保存新修订。

`GET /api/v1/admin/contents/{id}/revisions?page=1&pageSize=20`：page>=1，pageSize1～50；200既有分页结构，items只含id、articleVersion、operation（BASELINE/CREATE/SAVE/PUBLISH/UNPUBLISH/SUMMARY/RESTORE）、title、summary、createdAt UTC，按articleVersion倒序，列表不返回正文。不存在内容404ARTICLE_NOT_FOUND；非法分页400VALIDATION_FAILED。

`GET /api/v1/admin/contents/{id}/revisions/{revisionId}`：200上述元数据加snapshot（title/summary/bodyMarkdown/tags/coverUrl/category）；只可读取该篇修订，缺失或错误归属404CONTENT_REVISION_NOT_FOUND。快照是当时工作稿，不承诺保留每次对外发布的全部旧快照。

`POST /api/v1/admin/contents/{id}/revisions/{revisionId}/restore`：管理员+CSRF，JSON `{expectedVersion: number}` 必填。锁定内容并核对当前版本，不符409ARTICLE_VERSION_CONFLICT；恢复历史工作稿正文/标题/摘要/标签/分类/封面，保留当前UUID/slug/type/status、公开快照和首次发布日。200当前AdminContent，版本+1、添加RESTORE修订，不自动发布/调用模型，若当前已发布仅产生未发布工作稿。缺失/错误归属404；现有保存规则仍生效，非法400。管理台有未保存编辑时要求先保存，恢复前预览/确认可取消。

所属图片由现有附件存储保留，历史Markdown仍引用原图片，不复制二进制；修订恢复不是磁盘图片、整库、评论或配置恢复。永久删除内容级联删除修订。旧Hugo迁移暂缓，部署等用户确认；未来回收站语义另行扩展。
