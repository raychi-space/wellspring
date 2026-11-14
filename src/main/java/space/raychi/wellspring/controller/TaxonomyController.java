package space.raychi.wellspring.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import space.raychi.wellspring.dto.TaxonomyManagement.Item;
import space.raychi.wellspring.dto.TaxonomyManagement.Reference;
import space.raychi.wellspring.dto.TaxonomyManagement.Rename;
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

    @GetMapping("/api/v1/admin/taxonomy/{kind}")
    ResponseEntity<List<Item>> management(@PathVariable String kind) {
        return ApiResponses.ok(taxonomy.management(TaxonomyService.kind(kind)));
    }

    @GetMapping("/api/v1/admin/taxonomy/{kind}/references")
    ResponseEntity<List<Reference>> references(
            @PathVariable String kind, @RequestParam String name) {
        return ApiResponses.ok(taxonomy.references(TaxonomyService.kind(kind), name));
    }

    @PutMapping("/api/v1/admin/taxonomy/{kind}")
    ResponseEntity<Void> rename(@PathVariable String kind,
            @RequestBody Rename input) {
        taxonomy.rename(TaxonomyService.kind(kind), input);
        return ApiResponses.noContent();
    }

    @DeleteMapping("/api/v1/admin/taxonomy/{kind}")
    ResponseEntity<Void> delete(@PathVariable String kind,
            @RequestParam String name) {
        taxonomy.delete(TaxonomyService.kind(kind), name);
        return ApiResponses.noContent();
    }

    @PostMapping("/api/v1/admin/categories")
    ResponseEntity<NameDto> createCategory(@RequestBody NameDto input) {
        return ApiResponses.ok(taxonomy.createCategory(input));
    }

    @PostMapping("/api/v1/admin/tags")
    ResponseEntity<NameDto> createTag(@RequestBody NameDto input) {
        return ApiResponses.ok(taxonomy.createTag(input));
    }
}
