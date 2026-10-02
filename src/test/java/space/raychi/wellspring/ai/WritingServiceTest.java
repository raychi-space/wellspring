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
