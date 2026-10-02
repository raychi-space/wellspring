package space.raychi.wellspring.service;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import space.raychi.wellspring.entity.PublicationSummary;
import space.raychi.wellspring.mapper.ArticleMapper;
import space.raychi.wellspring.mapper.PublicationSummaryMapper;
import space.raychi.wellspring.search.SearchState;

@Service
public class PublicationSummaryService {
    private final ArticleMapper articles;
    private final PublicationSummaryMapper jobs;
    private final SearchState search;
    public PublicationSummaryService(ArticleMapper articles, PublicationSummaryMapper jobs, SearchState search) {
        this.articles = articles; this.jobs = jobs; this.search = search;
    }

    @Transactional
    public void enqueue(String articleId, String assistantId) {
        var article = articles.selectById(articleId, false).orElseThrow();
        if (article.type().equals("ARTICLE"))
            jobs.enqueue(articleId, UUID.randomUUID().toString(), assistantId, article.publicUpdatedAt());
    }

    /** Lock order matches publishing: article first, then job. Never apply to a later publication. */
    @Transactional
    public void complete(PublicationSummary job, String summary) {
        var article = articles.selectById(job.articleId(), true).orElse(null);
        var current = jobs.get(job.articleId()).orElse(null);
        if (article == null || current == null || !current.jobId().equals(job.jobId())) return;
        if (!article.status().equals("PUBLISHED") || !Objects.equals(article.publicUpdatedAt(), job.publicationAt())) {
            jobs.finish(job.jobId(), "CANCELLED", null); return;
        }
        if (!current.status().equals("RUNNING")) return;
        if (summary == null || summary.isBlank() || summary.length() > 600) {
            jobs.finish(job.jobId(), "FAILED", "INVALID_OUTPUT"); return;
        }
        // Draft edits made after publishing retain their own summary and remain private.
        boolean draftMatches = Objects.equals(article.draftBody(), article.publicBody())
                && Objects.equals(article.draftTitle(), article.publicTitle())
                && Objects.equals(article.draftSummary(), article.publicSummary());
        articles.applyPublicationSummary(article.id(), summary.strip(), draftMatches);
        jobs.finish(job.jobId(), "SUCCEEDED", null);
        search.capture(article.id(), false);
    }
}
