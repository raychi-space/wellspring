# 相关文章 v1（兼容只读扩展）

主任务 [raychi#56](https://github.com/raychi-space/raychi/issues/56)，后端 [#30](https://github.com/raychi-space/wellspring/issues/30)。消费方 lantern；不改现有内容、搜索或模型接口，无新增数据迁移。

`GET /api/v1/public/contents/ARTICLE/{slug}/related?limit=3`，匿名读取，无会话或 CSRF 要求；`Cache-Control: no-store`。limit 默认3，整数1～6；越界/非法400 `VALIDATION_FAILED`。目标必须是当前已发布文章；未发布、撤回、不存在统一404 `ARTICLE_NOT_FOUND`。只提供 ARTICLE 路径，POST 无该路由。

成功200返回 JSON 数组，长度0～limit。每项只含 `id`（UUID）、`slug`、`title`、`summary`、可空 `category`、`publishedAt`（首次发布日期UTC ISO8601）。不含正文、工作稿、版本、内部得分或管理字段。错误采用既有 `code/message/requestId/fieldErrors`，响应有 `X-Request-Id`。

基于目标与候选的**当前发布快照**：每个完全相同共享标签3分，相同非“未分类”分类1分；仅正分候选，排除目标自身及所有帖子、草稿、撤回。标签大小写敏感，中文与特殊字符精确匹配；同分按 publishedAt降序、id降序。没有匹配时返回空数组，不随机补推荐。工作稿修改不改变关系，重新明确发布才更新标签/分类索引；再次发布不改变首次日期。

数据库执行匹配/排序/限量，只选择公开元数据，目标与候选在只读事务里一致读取。复用现有发布标签索引，不全量加载正文或调用模型。客户端应将该信息作为可选阅读辅助；请求失败或超时可隐藏相关文章，正文路径照常处理。

例如目标标签 `["Java", "数据库"]`、分类“技术”：两标签候选得6分，同分类+一个标签得4分，仅同分类得1分，`java`与`Java`不作为共享标签；缺少共同标签且均为“未分类”则不相关。
