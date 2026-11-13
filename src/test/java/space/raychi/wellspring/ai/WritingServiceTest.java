package space.raychi.wellspring.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import space.raychi.wellspring.api.ApiException;

class WritingServiceTest {
  private final ObjectMapper json = new ObjectMapper();
  private final AgentClient client = mock(AgentClient.class);
  private final WritingService service = new WritingService(client, json);

  @Test
  void insertionUsesUnsavedSnapshotAndExactlyOneScopedCollector() throws Exception {
    final String[] origin = {null};
    when(client.request(eq("POST"), eq("/runs"), any(), any())).thenAnswer(call -> {
      var run = (com.fasterxml.jackson.databind.JsonNode) call.getArgument(2);
      origin[0] = run.path("origin").asText();
      assertThat(run.path("resultRequired").asBoolean()).isTrue();
      assertThat(run.path("snapshots").path("document").path("documentMarkdown").asText()).isEqualTo("未保存正文");
      assertThat(run.path("snapshots").path("insertion").path("contextBefore").asText()).isEqualTo("光标之前");
      var bindings = run.path("bindings");
      assertThat(bindings.size()).isEqualTo(3);
      assertThat(bindings.get(0).path("name").asText()).isEqualTo("read_document");
      assertThat(bindings.get(1).path("name").asText()).isEqualTo("read_insertion");
      assertThat(bindings.get(1).path("executor").asText()).isEqualTo("snapshot.read");
      var collector = bindings.get(2);
      assertThat(collector.path("name").asText()).isEqualTo("propose_insertion");
      assertThat(collector.path("executor").asText()).isEqualTo("result.collect");
      assertThat(collector.path("schema").path("properties").path("insertionId").path("const").asText()).isEqualTo("caret-1");
      assertThat(collector.path("schema").path("properties").path("newText").path("minLength").asInt()).isEqualTo(1);
      assertThat(collector.path("schema").path("properties").path("newText").path("maxLength").asInt()).isEqualTo(100000);
      assertThat((String) call.getArgument(3)).startsWith("raychi.writing:").endsWith(":insert-1");
      return json.createObjectNode().put("id", "task-insert").put("status", "pending");
    });
    service.create(json.readTree("""
      {"requestId":"insert-1","assistantId":"assistant","mode":"insert","message":"写一段","history":[],"context":{"title":"","documentMarkdown":"未保存正文","insertion":{"insertionId":"caret-1","contextBefore":"光标之前","contextAfter":""}}}
      """), "owner");
    when(client.request(eq("GET"), eq("/tasks/task-insert"), isNull(), isNull())).thenReturn(json.createObjectNode().put("origin", origin[0]).put("taskType", "configured_run").put("status", "succeeded"));
    when(client.request(eq("GET"), eq("/tasks/task-insert/result"), isNull(), isNull())).thenReturn(json.readTree("""
      {"result":{"reply":"已生成建议","collected":{"insertionId":"caret-1","newText":"新增 **内容**"}}}
      """));
    var proposal = service.get("task-insert", "owner").path("result").path("proposal");
    assertThat(proposal.path("kind").asText()).isEqualTo("insertion");
    assertThat(proposal.path("newText").asText()).isEqualTo("新增 **内容**");
    when(client.request(eq("GET"), eq("/tasks/task-insert/result"), isNull(), isNull())).thenReturn(json.readTree("""
      {"result":{"collected":{"insertionId":"caret-1","newText":"  "}}}
      """));
    assertThatThrownBy(() -> service.get("task-insert", "owner")).isInstanceOf(ApiException.class);
  }

  @Test
  void insertionRejectsMissingMixedAndOutOfModeTargets() throws Exception {
    var request = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
      {"requestId":"insert-1","assistantId":"assistant","mode":"insert","message":"写一段","history":[],"context":{"title":"","documentMarkdown":""}}
      """);
    assertThatThrownBy(() -> service.create(request, "owner")).isInstanceOf(ApiException.class);
    var context = (com.fasterxml.jackson.databind.node.ObjectNode) request.path("context");
    context.set("insertion", json.readTree("""
      {"insertionId":"caret-1","contextBefore":"","contextAfter":""}
      """));
    context.putObject("selection").put("selectionId", "selection-1");
    assertThatThrownBy(() -> service.create(request, "owner")).isInstanceOf(ApiException.class);
    context.remove("selection");
    request.put("mode", "chat");
    assertThatThrownBy(() -> service.create(request, "owner")).isInstanceOf(ApiException.class);
    request.put("mode", "insert");
    ((com.fasterxml.jackson.databind.node.ObjectNode) context.path("insertion")).put("contextAfter", "x".repeat(501));
    assertThatThrownBy(() -> service.create(request, "owner")).isInstanceOf(ApiException.class);
    verifyNoInteractions(client);
  }

  @Test
  void wholeDocumentProposalIsOptionalScopedAndStreamsOwnedText() throws Exception {
    final String[] origin = {null};
    when(client.request(eq("POST"), eq("/runs"), any(), any())).thenAnswer(call -> {
      var run = (com.fasterxml.jackson.databind.JsonNode) call.getArgument(2);
      origin[0] = run.path("origin").asText();
      assertThat(run.path("resultRequired").asBoolean()).isFalse();
      assertThat(run.path("snapshots").path("document").path("documentMarkdown").asText()).isEqualTo("未保存正文");
      var collector = run.path("bindings").get(1);
      assertThat(collector.path("name").asText()).isEqualTo("propose_document");
      assertThat(collector.path("schema").path("properties").path("documentId").path("const").asText()).isEqualTo("snapshot-1");
      return json.createObjectNode().put("id", "task-doc").put("status", "pending");
    });
    service.create(json.readTree("""
      {"requestId":"doc","assistantId":"assistant","mode":"chat","message":"修改全文","history":[],"context":{"title":"","documentId":"snapshot-1","documentMarkdown":"未保存正文"}}
      """), "owner");
    var task = json.createObjectNode().put("origin", origin[0]).put("taskType", "configured_run").put("status", "running").put("partialReply", "正在修改");
    when(client.request(eq("GET"), eq("/tasks/task-doc"), isNull(), isNull())).thenReturn(task);
    assertThat(service.get("task-doc", "owner").path("partialReply").asText()).isEqualTo("正在修改");
    assertThatThrownBy(() -> service.get("task-doc", "other")).isInstanceOf(ApiException.class);
    task.put("status", "succeeded");
    when(client.request(eq("GET"), eq("/tasks/task-doc/result"), isNull(), isNull())).thenReturn(json.readTree("""
      {"result":{"reply":"完成","collected":{"documentId":"snapshot-1","newText":"修改全文"}}}
      """));
    assertThat(service.get("task-doc", "owner").path("result").path("proposal").path("kind").asText()).isEqualTo("document");
  }

  @Test
  void metadataRequiresStructuredFieldsAndBuildsFiveWordSlug() throws Exception {
    final String[] origin = {null};
    when(client.request(eq("POST"), eq("/runs"), any(), any())).thenAnswer(call -> {
      var run = (com.fasterxml.jackson.databind.JsonNode) call.getArgument(2);
      origin[0] = run.path("origin").asText();
      var tool = run.path("bindings").get(1);
      assertThat(tool.path("name").asText()).isEqualTo("propose_metadata");
      assertThat(tool.path("schema").path("required").toString()).contains("title", "summary", "englishTitle");
      return json.readTree("{\"id\":\"task-meta\",\"status\":\"pending\"}");
    });
    service.create(json.readTree("""
      {"requestId":"meta","assistantId":"assistant","mode":"metadata","message":"生成","history":[],"context":{"title":"标题","documentMarkdown":"# 正文"}}
      """), "owner");
    when(client.request(eq("GET"), eq("/tasks/task-meta"), isNull(), isNull())).thenReturn(json.createObjectNode().put("origin", origin[0]).put("status", "succeeded").put("taskType", "configured_run"));
    when(client.request(eq("GET"), eq("/tasks/task-meta/result"), isNull(), isNull())).thenReturn(json.readTree("""
      {"result":{"reply":"","collected":{"title":"大标题","summary":"文章摘要","englishTitle":"How to Build Better Writing Tools Today"}}}
      """));
    var proposal = service.get("task-meta", "owner").path("result").path("proposal");
    assertThat(proposal.path("kind").asText()).isEqualTo("metadata");
    assertThat(proposal.path("slug").asText()).isEqualTo("how-to-build-better-writing");
    when(client.request(eq("GET"), eq("/tasks/task-meta/result"), isNull(), isNull())).thenReturn(json.readTree("""
      {"result":{"collected":{"title":"标题","summary":"摘要","englishTitle":"中文"}}}
      """));
    assertThatThrownBy(() -> service.get("task-meta", "owner")).isInstanceOf(ApiException.class);
  }

  @Test
  void rewriteBindsExactSelectionAndUsesScopedIdempotency() throws Exception {
    when(client.request(eq("POST"), eq("/runs"), any(), any()))
        .thenAnswer(
            call -> {
              var run = (com.fasterxml.jackson.databind.JsonNode) call.getArgument(2);
              assertThat(run.path("snapshots").path("selection").path("beforeMarkdown").asText())
                  .isEqualTo("相同原文");
              assertThat(run.path("bindings").get(0).path("executor").asText())
                  .isEqualTo("snapshot.read");
              assertThat(
                      run.path("bindings")
                          .get(1)
                          .path("schema")
                          .path("properties")
                          .path("selectionId")
                          .path("const")
                          .asText())
                  .isEqualTo("selection-1");
              assertThat(run.path("resultRequired").asBoolean()).isTrue();
              assertThat((String) call.getArgument(3))
                  .startsWith("raychi.writing:")
                  .endsWith(":request-1")
                  .doesNotContain("test-owner");
              return json.readTree("{\"id\":\"task-1\",\"status\":\"pending\"}");
            });
    var response =
        service.create(
            json.readTree(
                """
                {"requestId":"request-1","assistantId":"assistant-1","mode":"rewrite","message":"润色","history":[],"context":{"title":"标题","selection":{"selectionId":"selection-1","beforeMarkdown":"相同原文","contextBefore":"","contextAfter":""}}}
                """),
            "test-owner");
    assertThat(response.path("turnId").asText()).isEqualTo("task-1");
  }

  @Test
  void summaryUsesFullUnsavedSnapshotAndChatDoesNotGrantCollector() throws Exception {
    when(client.request(eq("POST"), eq("/runs"), any(), any()))
        .thenAnswer(
            call -> {
              var run = (com.fasterxml.jackson.databind.JsonNode) call.getArgument(2);
              assertThat(run.path("snapshots").path("document").path("documentMarkdown").asText())
                  .isEqualTo("尚未保存的正文");
              assertThat(run.path("bindings").size())
                  .isEqualTo(run.path("resultRequired").asBoolean() ? 2 : 1);
              return json.readTree("{\"id\":\"task-1\",\"status\":\"pending\"}");
            });
    for (String mode : new String[] {"summarize", "chat"})
      service.create(
          json.readTree(
              """
              {"requestId":"request-1","assistantId":"assistant-1","mode":"%s","message":"建议","history":[{"role":"user","content":"上一轮"},{"role":"assistant","content":"已拒绝"}],"context":{"title":"标题","documentMarkdown":"尚未保存的正文","currentSummary":"旧摘要"}}
              """
                  .formatted(mode)),
          "owner");
  }

  @Test
  void chatWithQuotedSelectionAllowsOptionalProposalButDoesNotRequireAnEdit() throws Exception {
    when(client.request(eq("POST"), eq("/runs"), any(), any()))
        .thenAnswer(
            call -> {
              var run = (com.fasterxml.jackson.databind.JsonNode) call.getArgument(2);
              assertThat(run.path("resultRequired").asBoolean()).isFalse();
              assertThat(run.path("bindings").size()).isEqualTo(3);
              var collector = run.path("bindings").get(2);
              assertThat(collector.path("name").asText()).isEqualTo("propose_replacement");
              assertThat(
                      collector
                          .path("schema")
                          .path("properties")
                          .path("selectionId")
                          .path("const")
                          .asText())
                  .isEqualTo("selection-1");
              assertThat(run.path("messages").get(0).path("content").asText())
                  .contains(
                      "Default to normal conversation", "explicitly asks", "do not propose edits");
              return json.readTree("{\"id\":\"task-1\",\"status\":\"pending\"}");
            });
    for (String message : new String[] {"这段在讲什么？", "请把这段改得简洁"})
      service.create(
          json.readTree(
              """
              {"requestId":"request-1","assistantId":"assistant-1","mode":"chat","message":"%s","history":[],"context":{"title":"标题","documentMarkdown":"尚未保存的全文","selection":{"selectionId":"selection-1","beforeMarkdown":"原文","contextBefore":"","contextAfter":""}}}
              """
                  .formatted(message)),
          "owner");
  }

  @Test
  void invalidHistoryMissingSelectionAndInjectedToolsAreRejected() throws Exception {
    var request =
        json.readTree(
            """
            {"requestId":"request-1","assistantId":"assistant-1","mode":"rewrite","message":"建议","history":[],"context":{"title":"标题"}}
            """);
    assertThatThrownBy(() -> service.create(request, "owner")).isInstanceOf(ApiException.class);
    ((com.fasterxml.jackson.databind.node.ObjectNode) request).put("tools", "shell");
    assertThatThrownBy(() -> service.create(request, "owner")).isInstanceOf(ApiException.class);
    verifyNoInteractions(client);
  }

  @Test
  void taskQueryChecksSourceBeforeReadingResultAndSanitizesErrors() throws Exception {
    when(client.request(eq("GET"), eq("/tasks/task-1"), isNull(), isNull()))
        .thenReturn(
            json.readTree(
                "{\"taskType\":\"configured_run\",\"status\":\"succeeded\",\"origin\":\"another-caller\"}"));
    assertThatThrownBy(() -> service.get("task-1", "owner")).isInstanceOf(ApiException.class);
    verify(client, never()).request(eq("GET"), eq("/tasks/task-1/result"), any(), any());
  }
}
