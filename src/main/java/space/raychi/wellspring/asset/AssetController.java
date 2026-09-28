package space.raychi.wellspring.asset;

import space.raychi.wellspring.api.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class AssetController {
    private final AssetService assets;

    AssetController(AssetService assets) { this.assets = assets; }

    @PostMapping(path = "/api/v1/admin/articles/{id}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<AssetService.Uploaded> upload(@PathVariable String id, @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(assets.upload(id, file));
    }

    @RequestMapping(path = "/api/v1/admin/assets/{id}/content", method = {RequestMethod.GET, RequestMethod.HEAD})
    ResponseEntity<byte[]> adminImage(@PathVariable String id, HttpServletRequest request) {
        return image(id, true, request);
    }

    @RequestMapping(path = "/api/v1/public/assets/{id}/content", method = {RequestMethod.GET, RequestMethod.HEAD})
    ResponseEntity<byte[]> publicImage(@PathVariable String id, HttpServletRequest request) {
        return image(id, false, request);
    }

    private ResponseEntity<byte[]> image(String id, boolean admin, HttpServletRequest request) {
        AssetService.Content content = assets.read(id, admin);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(content.mediaType()))
                .contentLength(content.bytes().length)
                .body(request.getMethod().equals("HEAD") ? null : content.bytes());
    }
}
