# 内容回收站 v1

主任务raychi#62，后端#36、消费方inkwell#37。新增V12 `trashed_at` 和内部TRASHED状态。管理员/CSRF、no-store与X-Request-Id沿用现有规则；所有写入要求非负expectedVersion，缺失/非法400VALIDATION_FAILED，不符409ARTICLE_VERSION_CONFLICT。

- `POST /api/v1/admin/contents/{id}/trash`，JSON `{expectedVersion}`：仅现存未入回收站内容，200 TrashItem。锁行、版本+1，保留工作稿/最近发布快照/地址/日期/所属图片与修订，记录TRASH修订，取消摘要、发搜索删除投影。公开读取/列表/订阅/地图/相关文章/图片立即不可见（历史搜索命中再次校验公开状态）。
- `GET /api/v1/admin/trash?page=1&pageSize=20`：200分页TrashItem，仅元数据id/slug/type/title/version/trashedAt/updatedAt，按移入时间及id倒序，无正文；page>=1、pageSize1～50，非法400。
- `POST /api/v1/admin/trash/{id}/restore`，JSON `{expectedVersion}`：只恢复TRASHED内容，200既有AdminContent，DRAFT/版本+1，清除trashed_at、记录RECOVER修订。保留UUID/slug/type/快照/首次发布日期与图片/历史，必须另行手动发布；不调用模型。
- `DELETE /api/v1/admin/trash/{id}?expectedVersion=...`：仅TRASHED，204/no-store，锁行核对版本，内容/历史/附件元数据与附件删除队列同事务；失败回滚不删磁盘文件，文件由现有可重试回收器处理。管理台要求明确永久删除确认。

错误或状态不匹配404ARTICLE_NOT_FOUND。原普通管理员获取/保存/发布/撤回/导出/历史详情和恢复拒绝TRASHED内容404；普通管理列表默认排除回收站，新上传拒绝TRASHED，管理员已有图片读取仍可用。回收站没有自动过期/清空，不提供批量永久删除。

兼容边界：旧 `DELETE /admin/contents/{id}` 仍永久删除未入回收站内容，供既有API客户保留行为；管理台新按钮只使用POST移入，永久删除只从回收站明确确认。最近100修订保留策略不变，TRASH/RECOVER也占修订条目；旧Hugo不迁移，部署等待用户确认。
