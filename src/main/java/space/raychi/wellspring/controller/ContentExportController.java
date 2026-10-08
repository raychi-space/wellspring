package space.raychi.wellspring.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import space.raychi.wellspring.api.ApiResponses;
import space.raychi.wellspring.service.ContentExportService;

@RestController
public class ContentExportController {
    private final ContentExportService exports;
    ContentExportController(ContentExportService exports) { this.exports = exports; }
    @GetMapping("/api/v1/admin/contents/{id}/export")
    ResponseEntity<byte[]> export(@PathVariable String id, @RequestParam long expectedVersion) {
        var download = exports.export(id, expectedVersion);
        return ApiResponses.download(download.bytes(), download.filename());
    }
}
