package space.raychi.wellspring.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class WritingService {
  private final AgentClient client;
  private final ObjectMapper json;

  public WritingService(AgentClient client, ObjectMapper json) {
    this.client = client;
    this.json = json;
  }

  static void fields(JsonNode node, String... names) {
    if (node == null || !node.isObject()) throw AgentClient.error(422, "INVALID_INPUT");
    var allowed = Set.of(names);
    node.fieldNames()
        .forEachRemaining(
            name -> {
              if (!allowed.contains(name)) throw AgentClient.error(422, "INVALID_INPUT");
            });
  }

  static String string(JsonNode node, String name, int max, boolean empty) {
    var value = node.path(name);
    if (!value.isTextual() || value.asText().length() > max || (!empty && value.asText().isBlank()))
      throw AgentClient.error(422, "INVALID_INPUT");
    return value.asText();
  }

  static String id(String value) {
    if (!value.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}"))
      throw AgentClient.error(422, "INVALID_INPUT");
    return value;
  }

  private static String owner(String username) {
    try {
      return "raychi.writing:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(username.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Digest unavailable");
    }
  }

  public JsonNode create(JsonNode request, String username) {
    fields(request, "requestId", "assistantId", "mode", "message", "history", "context");
    String requestId = id(string(request, "requestId", 80, false)),
        assistantId = id(string(request, "assistantId", 80, false));
    String mode = string(request, "mode", 20, false),
        message = string(request, "message", 20000, false);
    if (!Set.of("chat", "rewrite", "summarize").contains(mode))
      throw AgentClient.error(422, "INVALID_INPUT");
    var context = request.path("context");
    fields(context, "title", "selection", "documentMarkdown", "currentSummary");
    String title = string(context, "title", 200, true);
    var messages = json.createArrayNode();
    var history = request.path("history");
    if (!history.isArray() || history.size() > 40 || history.size() % 2 != 0)
      throw AgentClient.error(422, "INVALID_INPUT");
    for (int i = 0; i < history.size(); i++) {
      var h = history.get(i);
      fields(h, "role", "content");
      if (!h.path("role").asText().equals(i % 2 == 0 ? "user" : "assistant"))
        throw AgentClient.error(422, "INVALID_INPUT");
      messages
          .addObject()
          .put("role", h.path("role").asText())
          .put("content", string(h, "content", 20000, false));
    }
    String instruction =
        switch (mode) {
          case "rewrite" ->
              "Read read_selection and submit exactly one propose_replacement with its exact"
                  + " selectionId. Only propose a change; do not claim it was applied.";
          case "summarize" ->
              "Read the complete read_document snapshot and submit exactly one propose_summary."
                  + " Only propose a summary; do not claim it was applied.";
          default ->
              context.has("selection")
                  ? "Use read_document and read_selection for context. Default to normal"
                        + " conversation. Only call propose_replacement when the latest user"
                        + " message explicitly asks to change the captured selection; for"
                        + " questions, discussion or advice, reply with text and do not propose"
                        + " edits. If proposing, use the exact selectionId. A proposal is never an"
                        + " applied change; the user must confirm it."
                  : "Use this turn's read_document for context. Give text advice only; no edit"
                        + " tools are granted. If asked to edit, ask the user to quote a valid"
                        + " selection first.";
        };
    messages
        .addObject()
        .put("role", "user")
        .put("content", message + "\n[Application instruction: " + instruction + "]");
    ObjectNode snapshots = json.createObjectNode();
    var bindings = json.createArrayNode();
    if (!mode.equals("rewrite")) {
      String document = string(context, "documentMarkdown", 200000, true);
      var doc =
          snapshots.putObject("document").put("title", title).put("documentMarkdown", document);
      if (mode.equals("summarize"))
        doc.put("currentSummary", string(context, "currentSummary", 2000, true));
      bindings.add(
          binding(
                  "read_document",
                  "snapshot.read",
                  "Read the current complete unsaved document snapshot.")
              .put("snapshotKey", "document"));
    }
    String selectionId = null;
    if (context.has("selection")) {
      var selection = context.get("selection");
      fields(selection, "selectionId", "beforeMarkdown", "contextBefore", "contextAfter");
      selectionId = id(string(selection, "selectionId", 80, false));
      string(selection, "beforeMarkdown", 20000, false);
      string(selection, "contextBefore", 500, true);
      string(selection, "contextAfter", 500, true);
      snapshots.set("selection", selection.deepCopy());
      bindings.add(
          binding(
                  "read_selection",
                  "snapshot.read",
                  "Read captured selection and surrounding context.")
              .put("snapshotKey", "selection"));
    }
    if (mode.equals("rewrite") && selectionId == null)
      throw AgentClient.error(422, "INVALID_INPUT");
    if (!mode.equals("chat") || selectionId != null) {
      var schema = json.createObjectNode().put("type", "object").put("additionalProperties", false);
      var properties = schema.putObject("properties");
      if (!mode.equals("summarize")) {
        properties.putObject("selectionId").put("type", "string").put("const", selectionId);
        properties.putObject("newText").put("type", "string").put("maxLength", 20000);
        schema.putArray("required").add("selectionId").add("newText");
      } else {
        properties
            .putObject("summary")
            .put("type", "string")
            .put("minLength", 1)
            .put("maxLength", 1000);
        schema.putArray("required").add("summary");
      }
      var collector =
          binding(
              mode.equals("summarize") ? "propose_summary" : "propose_replacement",
              "result.collect",
              "Submit one suggestion for user confirmation.");
      collector.set("schema", schema);
      bindings.add(collector);
    }
    var run =
        json.createObjectNode()
            .put("assistantId", assistantId)
            .put("origin", owner(username))
            .put("resultRequired", !mode.equals("chat"));
    run.set("messages", messages);
    run.set("snapshots", snapshots);
    run.set("bindings", bindings);
    var created = client.request("POST", "/runs", run, owner(username) + ":" + requestId);
    return json.createObjectNode()
        .put("turnId", created.path("id").asText())
        .put("status", created.path("status").asText());
  }

  private ObjectNode binding(String name, String executor, String description) {
    return json.createObjectNode()
        .put("name", name)
        .put("executor", executor)
        .put("description", description);
  }

  public JsonNode get(String taskId, String username) {
    id(taskId);
    var task = client.request("GET", "/tasks/" + taskId, null, null);
    if (!task.path("origin").asText().equals(owner(username))
        || !task.path("taskType").asText().equals("configured_run"))
      throw AgentClient.error(404, "NOT_FOUND");
    var out =
        json.createObjectNode().put("turnId", taskId).put("status", task.path("status").asText());
    if (task.path("status").asText().equals("succeeded")) {
      var result = client.request("GET", "/tasks/" + taskId + "/result", null, null).path("result");
      var converted = out.putObject("result").put("reply", result.path("reply").asText());
      if (result.has("collected")) {
        var proposal = result.path("collected").deepCopy();
        if (!(proposal instanceof ObjectNode object))
          throw AgentClient.error(502, "AGENT_PROTOCOL_ERROR");
        if (proposal.has("selectionId") && proposal.has("newText"))
          object.put("kind", "replacement");
        else if (proposal.has("summary")) object.put("kind", "summary");
        else throw AgentClient.error(502, "AGENT_PROTOCOL_ERROR");
        converted.set("proposal", proposal);
      }
    } else if (task.path("status").asText().equals("failed")) {
      String code = task.path("error").path("code").asText();
      String message =
          switch (code) {
            case "TIMEOUT" -> "模型任务超时，请重新生成。";
            case "INVALID_OUTPUT", "TOOL_FAILED", "TOOL_FORBIDDEN", "MODEL_PROTOCOL_ERROR" ->
                "模型未提交有效的建议，正文未改变。";
            case "CONTEXT_LIMIT", "TOKEN_LIMIT", "TURN_LIMIT", "TOOL_LIMIT", "OUTPUT_LIMIT" ->
                "任务超过资源限制，请调整助手配置或输入。";
            case "MODEL_CREDENTIAL_UNAVAILABLE" -> "模型密钥不可用，请检查服务商设置。";
            default -> "模型调用失败，请重试或检查配置。";
          };
      out.putObject("error")
          .put("code", code)
          .put("message", message)
          .put("retryable", task.path("error").path("retryable").asBoolean());
    }
    return out;
  }
}
