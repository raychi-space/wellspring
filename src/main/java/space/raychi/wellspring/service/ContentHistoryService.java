package space.raychi.wellspring.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.api.PageResponse;
import space.raychi.wellspring.dto.ContentArchive;
import space.raychi.wellspring.dto.ContentRevision;
import space.raychi.wellspring.dto.ContentRevisionSummary;
import space.raychi.wellspring.entity.ArticleEntity;
import space.raychi.wellspring.entity.ContentRevisionEntity;
import space.raychi.wellspring.mapper.ArticleMapper;
import space.raychi.wellspring.mapper.ContentRevisionMapper;

@Service
public class ContentHistoryService {
    private final ArticleMapper articles;
    private final ContentRevisionMapper revisions;
    private final ObjectMapper json;
    public ContentHistoryService(ArticleMapper articles, ContentRevisionMapper revisions, ObjectMapper json) {
        this.articles = articles; this.revisions = revisions; this.json = json;
    }
    /** Called only inside the content transaction, with its parent row locked. */
    public void baseline(ArticleEntity current) {
        if (revisions.count(current.id()) == 0) record(current, "BASELINE");
    }
    public void record(ArticleEntity row, String operation) {
        boolean article = row.type().equals("ARTICLE");
        revisions.insert(new ContentRevisionEntity(UUID.randomUUID().toString(), row.id(), row.version(), operation,
            row.draftTitle(), row.draftSummary(), row.draftBody(), row.draftTags() == null ? "[]" : row.draftTags(),
            article ? row.draftCategory() : null, row.draftCover(), Instant.now()));
    }
    @Transactional(readOnly = true)
    public PageResponse<ContentRevisionSummary> list(String articleId, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 50 || page > Integer.MAX_VALUE / pageSize)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "分页参数无效。");
        require(articleId);
        return new PageResponse<>(revisions.list(articleId, pageSize, (page - 1) * pageSize).stream().map(row ->
            new ContentRevisionSummary(row.id(), row.articleVersion(), row.operation(), row.title(), row.summary(), row.createdAt())).toList(),
            page, pageSize, revisions.count(articleId));
    }
    @Transactional(readOnly = true)
    public ContentRevision get(String articleId, String revisionId) {
        require(articleId);
        var row = revisions.get(articleId, revisionId).orElseThrow(() ->
            new ApiException(HttpStatus.NOT_FOUND, "CONTENT_REVISION_NOT_FOUND", "修订不存在或已超出保留范围。"));
        try {
            var snapshot = new ContentArchive.Snapshot(row.title(), row.summary(), row.bodyMarkdown(),
                json.readValue(row.tagsJson(), new TypeReference<List<String>>() {}), row.category(), row.coverUrl());
            return new ContentRevision(row.id(), row.articleVersion(), row.operation(), row.title(), row.summary(), row.createdAt(), snapshot);
        } catch (Exception ex) { throw new IllegalStateException("Invalid revision snapshot", ex); }
    }
    private void require(String id) {
        if (articles.selectById(id, false).filter(row -> !row.status().equals("TRASHED")).isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND", "内容不存在。");
    }
}
