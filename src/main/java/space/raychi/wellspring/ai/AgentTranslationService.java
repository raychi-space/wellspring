package space.raychi.wellspring.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class AgentTranslationService implements TranslationService {
    private static final String ORIGIN = "raychi.translation";
    private final AgentClient client;
    private final ObjectMapper json;

    public AgentTranslationService(AgentClient client, ObjectMapper json) {
        this.client = client; this.json = json;
    }

    @Override
    public String startTitleTranslation(String assistantId, String articleId, String originalTitle, String titleHash) {
        var messages = json.createArrayNode();
        messages.addObject().put("role", "user").put("content",
                "读取标题并提交简体中文译题。保留产品、模型、代码和专有名称；只翻译，不总结，不补充说明。");
        var snapshots = json.createObjectNode();
        snapshots.putObject("title").put("originalTitle", originalTitle);
        var bindings = json.createArrayNode();
        bindings.addObject().put("name", "read_title").put("description", "读取需要翻译的原标题。")
                .put("executor", "snapshot.read").put("snapshotKey", "title");
        var schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putObject("properties").putObject("translatedTitle").put("type", "string")
                .put("minLength", 1).put("maxLength", 500);
        schema.putArray("required").add("translatedTitle");
        bindings.addObject().put("name", "collect_translation").put("description", "提交最终的简体中文译题。")
                .put("executor", "result.collect").set("schema", schema);
        var run = json.createObjectNode().put("assistantId", assistantId).put("origin", ORIGIN)
                .put("resultRequired", true);
        run.set("messages", messages); run.set("snapshots", snapshots); run.set("bindings", bindings);
        var created = client.request("POST", "/runs", run,
                "translation:" + articleId + ":" + titleHash.substring(0, 16));
        return created.path("id").asText();
    }

    @Override
    public Task task(String taskId) {
        var task = client.request("GET", "/tasks/" + WritingService.id(taskId), null, null);
        if (!ORIGIN.equals(task.path("origin").asText()) || !"configured_run".equals(task.path("taskType").asText()))
            throw AgentClient.error(404, "NOT_FOUND");
        String status = task.path("status").asText();
        if ("succeeded".equals(status)) {
            var result = client.request("GET", "/tasks/" + taskId + "/result", null, null).path("result");
            String title = result.path("collected").path("translatedTitle").asText().trim();
            if (title.isEmpty() || title.length() > 500) throw AgentClient.error(502, "AGENT_PROTOCOL_ERROR");
            return new Task(taskId, status, title, null);
        }
        return new Task(taskId, status, null,
                "failed".equals(status) ? task.path("error").path("code").asText("MODEL_FAILED") : null);
    }
}
