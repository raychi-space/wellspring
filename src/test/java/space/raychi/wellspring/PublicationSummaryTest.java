package space.raychi.wellspring;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import space.raychi.wellspring.ai.AgentClient;
import space.raychi.wellspring.ai.PublicationSummaryWorker;
import space.raychi.wellspring.ai.WritingService;
import space.raychi.wellspring.dto.ArticleInput;
import space.raychi.wellspring.dto.VersionInput;
import space.raychi.wellspring.mapper.PublicationSummaryMapper;
import space.raychi.wellspring.service.ArticleService;
import space.raychi.wellspring.service.PublicationSummaryService;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:summary;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "raychi.admin.username=test", "raychi.admin.password-hash=$2a$10$trITbMJ1TRJPxkuerFtT3O.TE.JJCbAbIDZM/RB.QsbePc/FmuQui",
        "raychi.agent.summary-poll-ms=3600000"
})
class PublicationSummaryTest {
    @Autowired ArticleService articles;
    @Autowired PublicationSummaryWorker worker;
    @Autowired PublicationSummaryMapper jobs;
    @Autowired PublicationSummaryService summaries;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockitoBean WritingService writing;
    @MockitoBean AgentClient client;

    @BeforeEach void resetState() throws Exception {
        db.update("DELETE FROM publication_summaries");
        db.update("DELETE FROM articles");
        when(writing.create(any(), anyString())).thenReturn(json.readTree("{\"turnId\":\"task-demo\"}"));
        when(writing.get(anyString(), anyString())).thenReturn(json.readTree("{\"status\":\"succeeded\",\"result\":{\"proposal\":{\"kind\":\"summary\",\"summary\":\"发布版摘要\"}}}"));
    }

    private space.raychi.wellspring.dto.AdminArticle draft(String body) {
        var draft = articles.create("ARTICLE", null);
        return articles.save(draft.id(), new ArticleInput(draft.version(), draft.slug(), "旧客户端标题", "原摘要", body, List.of(), null, null));
    }
    private space.raychi.wellspring.dto.AdminArticle publish(String body) {
        var draft = draft(body);
        return articles.publish(draft.id(), new VersionInput(draft.version(), "assistant-demo"));
    }

    @Test void onlyPublishingQueuesSummaryAndBothSnapshotsPersist() {
        var draft = draft("# 发布版\n\n正文");
        assertThat(draft.summaryStatus()).isEqualTo("NONE");
        worker.poll(); verifyNoInteractions(writing);
        var published = articles.publish(draft.id(), new VersionInput(draft.version(), "assistant-demo"));
        assertThat(published.summaryStatus()).isEqualTo("PENDING");
        worker.poll(); assertThat(articles.getAdmin(draft.id()).summaryStatus()).isEqualTo("RUNNING");
        worker.poll();
        var complete = articles.getAdmin(draft.id());
        assertThat(complete.summaryStatus()).isEqualTo("SUCCEEDED");
        assertThat(complete.summary()).isEqualTo("发布版摘要");
        assertThat(complete.version()).isEqualTo(published.version() + 1);
        assertThat(complete.hasUnpublishedChanges()).isFalse();
        assertThat(articles.getPublic(complete.slug()).summary()).isEqualTo("发布版摘要");
        assertThat(db.queryForObject("SELECT version FROM search_sync_state WHERE document_id=?", Long.class, "content:" + complete.id())).isEqualTo(2);
        worker.poll(); verify(writing, times(1)).create(any(), anyString());
    }

    @Test void firstTopLevelHeadingWinsAndDateLinksAreStable() {
        var value = draft("```md\n# 假标题\n```\n\n> # 引用\n\n## **真实** [标题](https://example.com) `代码`\n\n# 第二个");
        assertThat(value.title()).isEqualTo("真实 标题 代码");
        assertThat(value.slug()).matches("\\d{4}-\\d{2}-\\d{2}-[a-f0-9]{8}");
        assertThat(draft("# 另一个").slug()).isNotEqualTo(value.slug());
        var published = articles.publish(value.id(), new VersionInput(value.version(), "assistant-demo"));
        var saved = articles.save(value.id(), new ArticleInput(published.version(), value.slug(), "忽略标题", "原摘要", "# 新正文标题", List.of(), null, null));
        assertThat(saved.slug()).isEqualTo(value.slug());
        assertThat(saved.title()).isEqualTo("新正文标题");
    }

    @Test void continuingDraftEditsDoesNotReceiveOldSummary() {
        var published = publish("# 发布正文"); worker.poll();
        var saved = articles.save(published.id(), new ArticleInput(published.version(), published.slug(), "私人", "私人摘要", "# 私人正文", List.of(), null, null));
        worker.poll();
        assertThat(articles.getAdmin(saved.id()).summary()).isEqualTo("私人摘要");
        assertThat(articles.getAdmin(saved.id()).bodyMarkdown()).isEqualTo("# 私人正文");
        assertThat(articles.getPublic(saved.slug()).summary()).isEqualTo("发布版摘要");
    }

    @Test void oldTaskCannotOverwriteRepublishOrWithdrawalAndDeleteCleansJob() {
        var published = publish("# 第一版"); worker.poll(); var old = jobs.get(published.id()).orElseThrow();
        var saved = articles.save(published.id(), new ArticleInput(published.version(), published.slug(), "", "新版摘要", "# 第二版", List.of(), null, null));
        var updated = articles.publish(saved.id(), new VersionInput(saved.version(), "assistant-demo"));
        summaries.complete(old, "不应出现");
        assertThat(articles.getPublic(saved.slug()).summary()).isEqualTo("新版摘要");
        worker.poll(); var currentJob = jobs.get(saved.id()).orElseThrow();
        var hidden = articles.unpublish(saved.id(), new VersionInput(updated.version()));
        summaries.complete(currentJob, "也不应出现");
        assertThat(articles.getAdmin(saved.id()).summary()).isEqualTo("新版摘要");
        assertThat(articles.getAdmin(saved.id()).summaryStatus()).isEqualTo("CANCELLED");
        articles.delete(saved.id(), new VersionInput(hidden.version())); assertThat(jobs.get(saved.id())).isEmpty();
    }

    @Test void failureAndTimeoutPreservePublishedContent() throws Exception {
        var published = publish("# 模型故障"); worker.poll();
        when(writing.get(anyString(), anyString())).thenReturn(json.readTree("{\"status\":\"failed\",\"error\":{\"code\":\"MODEL_FAILURE\"}}"));
        worker.poll();
        assertThat(articles.getAdmin(published.id()).summaryStatus()).isEqualTo("FAILED");
        assertThat(articles.getPublic(published.slug()).summary()).isEqualTo("原摘要");
        var next = publish("# 超时"); worker.poll();
        when(writing.get(anyString(), anyString())).thenReturn(json.readTree("{\"status\":\"running\"}"));
        db.update("UPDATE publication_summaries SET started_at=? WHERE article_id=?", Timestamp.from(Instant.now().minusSeconds(160)), next.id());
        worker.poll(); assertThat(articles.getAdmin(next.id()).summaryError()).isEqualTo("TIMEOUT");
        assertThat(articles.getPublic(next.slug()).bodyMarkdown()).isEqualTo("# 超时");
    }

    @Test void completedResultIsRecoveredEvenAfterLongApiDowntime() {
        var published = publish("# 重启恢复"); worker.poll();
        db.update("UPDATE publication_summaries SET started_at=? WHERE article_id=?", Timestamp.from(Instant.now().minusSeconds(600)), published.id());
        worker.poll();
        assertThat(articles.getAdmin(published.id()).summaryStatus()).isEqualTo("SUCCEEDED");
        assertThat(articles.getPublic(published.slug()).summary()).isEqualTo("发布版摘要");
        verify(writing, times(1)).create(any(), anyString());
    }

    @Test void noEnabledAssistantSkipsWithoutBlockingPublication() throws Exception {
        var draft = draft("# 无助手");
        when(client.request(eq("GET"), eq("/assistants"), isNull(), isNull())).thenReturn(json.readTree("{\"items\":[]}"));
        var published = articles.publish(draft.id(), new VersionInput(draft.version())); worker.poll();
        assertThat(articles.getAdmin(published.id()).summaryStatus()).isEqualTo("SKIPPED");
        assertThat(articles.getPublic(published.slug()).title()).isEqualTo("无助手");
    }
}
