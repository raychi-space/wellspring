package space.raychi.wellspring.controller;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import space.raychi.wellspring.api.ApiResponses;
import space.raychi.wellspring.dto.NameDto;
import space.raychi.wellspring.service.TaxonomyService;

@RestController
public class TaxonomyController {
    private final TaxonomyService taxonomy;
    TaxonomyController(TaxonomyService taxonomy) { this.taxonomy = taxonomy; }

    @GetMapping("/api/v1/public/categories")
    ResponseEntity<List<NameDto>> categories() { return ApiResponses.ok(taxonomy.categories()); }

    @GetMapping("/api/v1/public/tags")
    ResponseEntity<List<NameDto>> tags() { return ApiResponses.ok(taxonomy.tags()); }

    @PostMapping("/api/v1/admin/categories")
    ResponseEntity<NameDto> createCategory(@RequestBody NameDto input) {
        return ApiResponses.ok(taxonomy.createCategory(input));
    }

    @PostMapping("/api/v1/admin/tags")
    ResponseEntity<NameDto> createTag(@RequestBody NameDto input) {
        return ApiResponses.ok(taxonomy.createTag(input));
    }
}
