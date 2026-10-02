package space.raychi.wellspring.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.entity.PublicationSummary;
import space.raychi.wellspring.mapper.ArticleMapper;
import space.raychi.wellspring.mapper.PublicationSummaryMapper;
import space.raychi.wellspring.service.PublicationSummaryService;

@Component
public class PublicationSummaryWorker {
    private static final String OWNER = "publication-summary";
    private final PublicationSummaryMapper jobs;
    private final ArticleMapper articles;
    private final PublicationSummaryService summaries;
    private final WritingService writing;
    private final AgentClient client;
    private final ObjectMapper json;
    private final ReentrantLock lock = new ReentrantLock();

    public PublicationSummaryWorker(PublicationSummaryMapper jobs, ArticleMapper articles,
            PublicationSummaryService summaries, WritingService writing, AgentClient client, ObjectMapper json) {
        this.jobs = jobs; this.articles = articles; this.summaries = summaries;
        this.writing = writing; this.client = client; this.json = json;
    }

    @Scheduled(scheduler = "publicationSummaryScheduler", fixedDelayString = "${raychi.agent.summary-poll-ms:2000}")
    public void poll() {
        if (!lock.tryLock()) return;
        try {
            for (String id : jobs.due()) {
                var job = jobs.get(id).orElse(null);
                if (job == null) continue;
                try { advance(job); }
                catch (ApiException ex) { jobs.finish(job.jobId(), "FAILED", ex.code()); }
                catch (RuntimeException ex) { jobs.finish(job.jobId(), "FAILED", "SUMMARY_UNAVAILABLE"); }
            }
        } finally { lock.unlock(); }
    }

    private void advance(PublicationSummary job) {
        var article = articles.selectById(job.articleId(), false).orElse(null);
        if (article == null || !article.status().equals("PUBLISHED")
                || !Objects.equals(article.publicUpdatedAt(), job.publicationAt())) {
            jobs.finish(job.jobId(), "CANCELLED", null); return;
        }
        if (job.status().equals("PENDING")) {
            String assistant = job.assistantId();
            if (assistant == null) {
                var items = client.request("GET", "/assistants", null, null).path("items");
                for (var item : items) if (item.path("enabled").asBoolean()) { assistant = item.path("id").asText(); break; }
                if (assistant == null) { jobs.finish(job.jobId(), "SKIPPED", "NO_ASSISTANT"); return; }
                jobs.choose(job.jobId(), assistant);
            }
            var request = json.createObjectNode().put("requestId", job.jobId()).put("assistantId", assistant)
                    .put("mode", "summarize").put("message", "为这次已发布的完整正文生成简洁摘要，不超过 600 个字符。");
            request.putArray("history");
            request.putObject("context").put("title", article.publicTitle())
                    .put("documentMarkdown", article.publicBody()).put("currentSummary", article.publicSummary());
            var created = writing.create(request, OWNER);
            jobs.running(job.jobId(), created.path("turnId").asText());
        } else {
            var task = writing.get(job.taskId(), OWNER);
            if (task.path("status").asText().equals("succeeded")) {
                var proposal = task.path("result").path("proposal");
                if (!proposal.path("kind").asText().equals("summary")) jobs.finish(job.jobId(), "FAILED", "INVALID_OUTPUT");
                else summaries.complete(job, proposal.path("summary").asText());
            } else if (task.path("status").asText().equals("failed")) {
                jobs.finish(job.jobId(), "FAILED", task.path("error").path("code").asText("MODEL_FAILURE"));
            } else if (job.startedAt() == null || Instant.now().isAfter(job.startedAt().plusSeconds(150))) {
                // Completed results remain recoverable after API downtime; only unfinished work expires.
                jobs.finish(job.jobId(), "FAILED", "TIMEOUT");
            }
        }
    }
}
