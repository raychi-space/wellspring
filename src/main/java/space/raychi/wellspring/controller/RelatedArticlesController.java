package space.raychi.wellspring.controller;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import space.raychi.wellspring.api.ApiResponses;
import space.raychi.wellspring.dto.RelatedArticle;
import space.raychi.wellspring.service.RelatedArticlesService;

@RestController
public class RelatedArticlesController {
    private final RelatedArticlesService related;
    RelatedArticlesController(RelatedArticlesService related) { this.related = related; }

    @GetMapping("/api/v1/public/contents/ARTICLE/{slug}/related")
    ResponseEntity<List<RelatedArticle>> articles(@PathVariable String slug,
            @RequestParam(defaultValue = "3") int limit) {
        return ApiResponses.okNoStore(related.articles(slug, limit));
    }
}
