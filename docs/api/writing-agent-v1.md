# 写作助手接入 v1

关联 [wellspring#10](https://github.com/raychi-space/wellspring/issues/10)、[raychi#13](https://github.com/raychi-space/raychi/issues/13)。管理台通过同源 `/api/v1/admin/ai`；全部接口需 ADMIN 会话，POST/PATCH 还需 CSRF。返回 no-store（前端使用 no-store；API 由安全链设置）。浏览器不接触 Agent 服务凭据；Provider Key 只提交至管理接口，随后查询仅 hasApiKey/apiKeyMask。

## 配置与操作

`GET/POST providers`、`PATCH providers/{id}`，`POST providers/{id}/test {model}`；`GET/POST assistants`、`PATCH assistants/{id}`。与 [agent-core 配置扩展](https://github.com/raychi-space/agent-core/blob/main/docs/configured-runs-v1.md) 字段一致，配置由独立内核 SQLite 保存。没有站点 Provider/Assistant 数据表。POST 创建201，PATCH/GET/测试200。

Provider：name/enabled/type=openai-compatible/baseUrl/models；apiKeyAction=retain（默认，省略 key）、replace（传 apiKey）、clear（明确清除，不传 key）。返回 id/version 与 hasApiKey、恒定掩码；无 key 或加密数据。baseUrl 是 `/chat/completions` 之前的前缀。支持 HTTPS，测试可用 loopback HTTP。

Assistant：可选 icon（`lucide:<slug>` 或 `emoji:<slug>`，最长 64 字符，省略时新建默认 `lucide:bot`、更新保留原值；仅用于展示）/name/enabled/providerId/model/systemPrompt/fixedContext/generationOptions.maxOutputTokens/historyTurns/maxContextChars/timeoutMs。保留最近完整轮次，全文不能静默截断。配置仅影响新任务；执行中的任务与原任务重试使用创建时快照。连接测试单独显示 connection 和 tools，不以只可聊天代表能执行修改。

## 写作任务

POST `turns` 返回202 `{turnId,status}`；GET `turns/{id}` 返回状态、成功 result 或失败 error，1秒轮询即可。状态 pending/running/succeeded/failed。

```ts
interface TurnRequest {
  requestId: string; assistantId: string; mode: 'chat'|'rewrite'|'summarize';
  message: string; history: {role:'user'|'assistant';content:string}[];
  context: {title:string; documentMarkdown?:string; currentSummary?:string;
    selection?:{selectionId:string;beforeMarkdown:string;contextBefore:string;contextAfter:string}};
}
```

requestId 为80字符内标识符；服务器加站主摘要范围作为底层幂等键。相同 payload 重发复用任务；不同 payload 同 key409。history 至多20完整用户/助手轮次；禁止 system/tool 消息。title200、message及每个历史消息20k；documentMarkdown200k；selection原文20k、前后文各500；currentSummary2000。内核助手字符预算还会校验整个上下文，过量422 CONTEXT_LIMIT，未截断全文。

chat 需要 documentMarkdown；read_document、可选 read_selection 只读取本轮快照。有有效selection时额外开放可选propose_replacement（固定selectionId Schema）；默认普通对话，模型仅在当前用户明确要求修改引用选区时才应提出建议，普通问答不要求收集结果。没有选区不开放编辑建议工具，提示先引用有效选区。所有建议仍须本地确认，模型不能直接修改正文。rewrite 必须有 selection，仅 read_selection 与 propose_replacement；结果 Schema 固定 selectionId 与 newText（可空，表示删除）。summarize 是受控生成建议接口；管理台不再在停顿时自动调用。发布摘要由后台任务使用已发布全文与摘要，提供 read_document 和 propose_summary（非空，最多1000）。工具名称、描述、权限与 Schema 由 wellspring 注入，浏览器不得提交绑定或 Schema；内核只执行通用 snapshot.read/result.collect。写作指令作为服务组装内容，用户提示词不能扩大工具授权。

成功 result `{reply,proposal?}`；proposal 为 `{kind:'replacement',selectionId,newText}` 或 `{kind:'summary',summary}`。该 turns 接口不修改正文、摘要或发布状态；独立的发布摘要任务在快照校验后持久化摘要，见内容契约。原文与位置由浏览器的当前编辑器书签验证，模型不提供位置。rewrite/summarize 缺有效收集结果时任务失败。

GET 根据底层调用者 owner、taskType=configured_run 与站主 origin 校验来源；不新建任务表，不暴露内核输入、配置快照或日志。失败 error `{code,message,retryable}`，仅安全错误码及中文原因，不返回模型错误正文。配置不可用422 CONFIG_UNAVAILABLE、非法输入422 INVALID_INPUT、幂等冲突409、服务未启用/凭据不可用/网络故障503 AGENT_UNAVAILABLE；任务可区分 TIMEOUT、MODEL_FAILED/REJECTED、INVALID_OUTPUT/TOOL_FAILED/FORBIDDEN、资源限制。网络重试复用 requestId；主动重新生成用新 ID。内核手动重试不在本期应用界面开放。

## 启动和回退

Agent 默认关闭：`RAYCHI_AGENT_ENABLED=true`、`RAYCHI_AGENT_URL=http://127.0.0.1:8092`、`RAYCHI_AGENT_TOKEN=<服务端调用凭据>`。在 core 配置匹配的调用者 scopes configure/run/read、持久 DB 和 AGENT_SECRET_KEY。不依赖 search-core，不跨库。后台保存 Provider，然后分别测试聊天/工具；保存 Assistant 后在文章编辑器选择它。

停用 Agent 集成可设 ENABLED=false；内容保存/发布功能照常可用。助手配置仍不存站点数据库；V7 增加发布摘要任务记录，见内容契约。core 数据库与加密主密钥需分别安全备份，历史任务不可通过改主密钥直接解密。

## 发布前元数据（2026-10-03）

新增写作 mode `metadata`，仍使用站长会话、CSRF、任务所有者隔离和现有 Agent 资源限制。context 提供 title 与 documentMarkdown；工具要求 title（1–200）、summary（1–600）、englishTitle（1–300）。成功 proposal.kind 为 metadata，同时返回 slug：英文标题小写、按非字母数字分词，取前五词用连字符连接（最长120）。无有效英文单词或超长结果拒绝。任务本身不保存或发布内容。

管理内容 PUT 可传 publicationMetadata=true，保留显式发布标题、摘要、别名；版本和别名唯一约束照常生效，首次发布后别名不可修改。发布 POST 可传 metadataReviewed=true，直接发布已确认工作稿，取消旧摘要任务且不再排队覆盖摘要。缺省 false 保留旧客户端兼容行为。界面先保存工作稿，再生成并保存元数据；仅确认发布才公开，模型失败保留工作稿，用户可重试。前端会等待任务完成，关闭浏览器可能中断元数据的回写，但已保存正文不会丢失。
