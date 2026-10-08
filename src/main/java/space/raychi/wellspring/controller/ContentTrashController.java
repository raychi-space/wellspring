package space.raychi.wellspring.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import space.raychi.wellspring.api.ApiResponses;
import space.raychi.wellspring.api.PageResponse;
import space.raychi.wellspring.dto.AdminArticle;
import space.raychi.wellspring.dto.TrashItem;
import space.raychi.wellspring.dto.VersionInput;
import space.raychi.wellspring.service.ContentTrashService;

@RestController
public class ContentTrashController {
    private final ContentTrashService trash;
    ContentTrashController(ContentTrashService trash) { this.trash=trash; }
    @GetMapping("/api/v1/admin/trash")
    ResponseEntity<PageResponse<TrashItem>> list(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize) { return ApiResponses.okNoStore(trash.list(page,pageSize)); }
    @PostMapping("/api/v1/admin/contents/{id}/trash")
    ResponseEntity<TrashItem> move(@PathVariable String id,@RequestBody VersionInput input) {
        return ApiResponses.okNoStore(trash.trash(id,input));
    }
    @PostMapping("/api/v1/admin/trash/{id}/restore")
    ResponseEntity<AdminArticle> restore(@PathVariable String id,@RequestBody VersionInput input) {
        return ApiResponses.okNoStore(trash.restore(id,input));
    }
    @DeleteMapping("/api/v1/admin/trash/{id}")
    ResponseEntity<Void> purge(@PathVariable String id,@RequestParam long expectedVersion) {
        trash.purge(id,new VersionInput(expectedVersion));
        return ApiResponses.noContentNoStore();
    }
}
