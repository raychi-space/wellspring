package space.raychi.wellspring.article;

import space.raychi.wellspring.api.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ArticleController {
    private final ArticleService articles;

    ArticleController(ArticleService articles) { this.articles = articles; }

    @GetMapping("/api/v1/public/contents")
    ArticleService.Page<ArticleService.PublicArticle> publicContents(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String type, @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag) {
        return articles.listPublic(page, pageSize, type, category, tag);
    }

    @GetMapping("/api/v1/public/contents/{type}/{slug}")
    ArticleService.PublicArticle publicContent(@PathVariable String type, @PathVariable String slug) {
        return articles.getPublic(type, slug);
    }

    @GetMapping("/api/v1/admin/contents")
    ArticleService.Page<ArticleService.AdminArticle> adminContents(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String status, @RequestParam(required = false) String type) {
        return articles.listAdmin(page, pageSize, status, type);
    }

    @PostMapping("/api/v1/admin/contents")
    ResponseEntity<ArticleService.AdminArticle> createContent(
            @RequestParam String type, @RequestBody(required = false) ArticleService.ArticleInput input) {
        ArticleService.AdminArticle created = articles.create(type, input);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", "/api/v1/admin/contents/" + created.id()).body(created);
    }

    @GetMapping("/api/v1/admin/contents/{id}")
    ArticleService.AdminArticle adminContent(@PathVariable String id) { return articles.getAdmin(id); }

    @PutMapping("/api/v1/admin/contents/{id}")
    ArticleService.AdminArticle saveContent(@PathVariable String id, @RequestBody ArticleService.ArticleInput input) {
        return articles.save(id, input);
    }

    @PostMapping("/api/v1/admin/contents/{id}/publish")
    ArticleService.AdminArticle publishContent(@PathVariable String id, @RequestBody ArticleService.VersionInput input) {
        return articles.publish(id, input);
    }

    @PostMapping("/api/v1/admin/contents/{id}/unpublish")
    ArticleService.AdminArticle unpublishContent(@PathVariable String id, @RequestBody ArticleService.VersionInput input) {
        return articles.unpublish(id, input);
    }

    @DeleteMapping("/api/v1/admin/contents/{id}")
    ResponseEntity<Void> deleteContent(@PathVariable String id, @RequestParam long expectedVersion) {
        articles.delete(id, new ArticleService.VersionInput(expectedVersion));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/public/articles")
    ArticleService.Page<ArticleService.PublicArticle> publicList(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int pageSize) {
        return articles.listPublic(page, pageSize);
    }

    @GetMapping("/api/v1/public/articles/{slug}")
    ArticleService.PublicArticle publicDetail(@PathVariable String slug) { return articles.getPublic(slug); }

    @GetMapping("/api/v1/admin/articles")
    ArticleService.Page<ArticleService.AdminArticle> adminList(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String status) {
        return articles.listAdmin(page, pageSize, status);
    }

    @PostMapping("/api/v1/admin/articles")
    ResponseEntity<ArticleService.AdminArticle> create(@RequestBody(required = false) ArticleService.ArticleInput input) {
        ArticleService.AdminArticle created = articles.create(input);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Location", "/api/v1/admin/articles/" + created.id()).body(created);
    }

    @GetMapping("/api/v1/admin/articles/{id}")
    ArticleService.AdminArticle adminDetail(@PathVariable String id) { return legacyArticle(id); }

    @PutMapping("/api/v1/admin/articles/{id}")
    ArticleService.AdminArticle save(@PathVariable String id, @RequestBody ArticleService.ArticleInput input) {
        legacyArticle(id);
        return articles.save(id, input);
    }

    @PostMapping("/api/v1/admin/articles/{id}/publish")
    ArticleService.AdminArticle publish(@PathVariable String id, @RequestBody ArticleService.VersionInput input) {
        legacyArticle(id);
        return articles.publish(id, input);
    }

    @PostMapping("/api/v1/admin/articles/{id}/unpublish")
    ArticleService.AdminArticle unpublish(@PathVariable String id, @RequestBody ArticleService.VersionInput input) {
        legacyArticle(id);
        return articles.unpublish(id, input);
    }

    private ArticleService.AdminArticle legacyArticle(String id) {
        ArticleService.AdminArticle content = articles.getAdmin(id);
        if (!"ARTICLE".equals(content.type()))
            throw new ApiException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND", "文章不存在。");
        return content;
    }
}
