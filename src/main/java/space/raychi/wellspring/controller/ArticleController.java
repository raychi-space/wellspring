package space.raychi.wellspring.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import space.raychi.wellspring.api.ApiResponses;
import space.raychi.wellspring.api.PageResponse;
import space.raychi.wellspring.dto.AdminArticle;
import space.raychi.wellspring.dto.ArticleInput;
import space.raychi.wellspring.dto.PublicArticle;
import space.raychi.wellspring.dto.VersionInput;
import space.raychi.wellspring.service.ArticleService;

@RestController
public class ArticleController {
    private final ArticleService articles;

    ArticleController(ArticleService articles) { this.articles = articles; }

    @GetMapping("/api/v1/public/contents")
    ResponseEntity<PageResponse<PublicArticle>> publicContents(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String type, @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag, @RequestParam(defaultValue = "latest") String sort) {
        return ApiResponses.ok(articles.listPublic(page, pageSize, type, category, tag, sort));
    }

    @GetMapping("/api/v1/public/contents/{type}/{slug}")
    ResponseEntity<PublicArticle> publicContent(@PathVariable String type, @PathVariable String slug) {
        return ApiResponses.ok(articles.getPublic(type, slug));
    }

    @GetMapping("/api/v1/admin/contents")
    ResponseEntity<PageResponse<AdminArticle>> adminContents(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String status, @RequestParam(required = false) String type,
            @RequestParam(required = false) String category, @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "recent") String sort) {
        return ApiResponses.ok(articles.listAdmin(page, pageSize, status, type, category, tag, sort));
    }

    @PostMapping("/api/v1/admin/contents")
    ResponseEntity<AdminArticle> createContent(
            @RequestParam String type, @RequestBody(required = false) ArticleInput input) {
        AdminArticle created = articles.create(type, input);
        return ApiResponses.created("/api/v1/admin/contents/" + created.id(), created);
    }

    @GetMapping("/api/v1/admin/contents/{id}")
    ResponseEntity<AdminArticle> adminContent(@PathVariable String id) { return ApiResponses.ok(articles.getAdmin(id)); }

    @PutMapping("/api/v1/admin/contents/{id}")
    ResponseEntity<AdminArticle> saveContent(@PathVariable String id, @RequestBody ArticleInput input) {
        return ApiResponses.ok(articles.save(id, input));
    }

    @PostMapping("/api/v1/admin/contents/{id}/publish")
    ResponseEntity<AdminArticle> publishContent(@PathVariable String id, @RequestBody VersionInput input) {
        return ApiResponses.ok(articles.publish(id, input));
    }

    @PostMapping("/api/v1/admin/contents/{id}/unpublish")
    ResponseEntity<AdminArticle> unpublishContent(@PathVariable String id, @RequestBody VersionInput input) {
        return ApiResponses.ok(articles.unpublish(id, input));
    }

    @DeleteMapping("/api/v1/admin/contents/{id}")
    ResponseEntity<Void> deleteContent(@PathVariable String id, @RequestParam long expectedVersion) {
        articles.delete(id, new VersionInput(expectedVersion));
        return ApiResponses.noContent();
    }

    @GetMapping("/api/v1/public/articles")
    ResponseEntity<PageResponse<PublicArticle>> publicList(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponses.ok(articles.listPublic(page, pageSize));
    }

    @GetMapping("/api/v1/public/articles/{slug}")
    ResponseEntity<PublicArticle> publicDetail(@PathVariable String slug) { return ApiResponses.ok(articles.getPublic(slug)); }

    @GetMapping("/api/v1/admin/articles")
    ResponseEntity<PageResponse<AdminArticle>> adminList(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String status) {
        return ApiResponses.ok(articles.listAdmin(page, pageSize, status));
    }

    @PostMapping("/api/v1/admin/articles")
    ResponseEntity<AdminArticle> create(@RequestBody(required = false) ArticleInput input) {
        AdminArticle created = articles.create(input);
        return ApiResponses.created("/api/v1/admin/articles/" + created.id(), created);
    }

    @GetMapping("/api/v1/admin/articles/{id}")
    ResponseEntity<AdminArticle> adminDetail(@PathVariable String id) { return ApiResponses.ok(articles.getLegacyArticle(id)); }

    @PutMapping("/api/v1/admin/articles/{id}")
    ResponseEntity<AdminArticle> save(@PathVariable String id, @RequestBody ArticleInput input) {
        return ApiResponses.ok(articles.saveLegacyArticle(id, input));
    }

    @PostMapping("/api/v1/admin/articles/{id}/publish")
    ResponseEntity<AdminArticle> publish(@PathVariable String id, @RequestBody VersionInput input) {
        return ApiResponses.ok(articles.publishLegacyArticle(id, input));
    }

    @PostMapping("/api/v1/admin/articles/{id}/unpublish")
    ResponseEntity<AdminArticle> unpublish(@PathVariable String id, @RequestBody VersionInput input) {
        return ApiResponses.ok(articles.unpublishLegacyArticle(id, input));
    }

}
