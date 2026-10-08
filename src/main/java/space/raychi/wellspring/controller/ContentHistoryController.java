package space.raychi.wellspring.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import space.raychi.wellspring.api.ApiResponses;
import space.raychi.wellspring.api.PageResponse;
import space.raychi.wellspring.dto.AdminArticle;
import space.raychi.wellspring.dto.ContentRevision;
import space.raychi.wellspring.dto.ContentRevisionSummary;
import space.raychi.wellspring.dto.VersionInput;
import space.raychi.wellspring.service.ArticleService;
import space.raychi.wellspring.service.ContentHistoryService;

@RestController
public class ContentHistoryController {
    private final ArticleService articles;
    private final ContentHistoryService history;
    ContentHistoryController(ArticleService articles, ContentHistoryService history) { this.articles = articles; this.history = history; }
    @GetMapping("/api/v1/admin/contents/{id}/revisions")
    ResponseEntity<PageResponse<ContentRevisionSummary>> list(@PathVariable String id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponses.okNoStore(history.list(id, page, pageSize));
    }
    @GetMapping("/api/v1/admin/contents/{id}/revisions/{revisionId}")
    ResponseEntity<ContentRevision> get(@PathVariable String id, @PathVariable String revisionId) {
        return ApiResponses.okNoStore(history.get(id, revisionId));
    }
    @PostMapping("/api/v1/admin/contents/{id}/revisions/{revisionId}/restore")
    ResponseEntity<AdminArticle> restore(@PathVariable String id, @PathVariable String revisionId, @RequestBody VersionInput input) {
        return ApiResponses.okNoStore(articles.restoreRevision(id, revisionId, input));
    }
}
