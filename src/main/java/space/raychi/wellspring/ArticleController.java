package space.raychi.wellspring;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
    ArticleService.AdminArticle adminDetail(@PathVariable String id) { return articles.getAdmin(id); }

    @PutMapping("/api/v1/admin/articles/{id}")
    ArticleService.AdminArticle save(@PathVariable String id, @RequestBody ArticleService.ArticleInput input) {
        return articles.save(id, input);
    }

    @PostMapping("/api/v1/admin/articles/{id}/publish")
    ArticleService.AdminArticle publish(@PathVariable String id, @RequestBody ArticleService.VersionInput input) {
        return articles.publish(id, input);
    }

    @PostMapping("/api/v1/admin/articles/{id}/unpublish")
    ArticleService.AdminArticle unpublish(@PathVariable String id, @RequestBody ArticleService.VersionInput input) {
        return articles.unpublish(id, input);
    }
}
