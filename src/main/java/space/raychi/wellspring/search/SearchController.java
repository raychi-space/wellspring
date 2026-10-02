package space.raychi.wellspring.search;

import java.util.Map;
import space.raychi.wellspring.dto.SearchResult;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class SearchController {
    private final SearchService search;
    public SearchController(SearchService search) { this.search = search; }

    @GetMapping("/api/v1/public/search")
    public ResponseEntity<SearchResult> search(@RequestParam MultiValueMap<String, String> input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(search.search(input));
    }
    @GetMapping("/api/v1/admin/search/status")
    public Map<String, Object> status() { return search.status(); }
    @GetMapping("/api/v1/admin/search/export")
    public ResponseEntity<Map<String, Object>> export() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(search.export());
    }
    @PostMapping("/api/v1/admin/search/retry")
    public Map<String, Integer> retry() { return search.retry(); }
    @PostMapping("/api/v1/admin/search/restore")
    public Map<String, Object> restore() { return search.restore(); }
}
