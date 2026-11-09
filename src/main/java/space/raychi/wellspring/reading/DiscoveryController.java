package space.raychi.wellspring.reading;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import space.raychi.wellspring.api.ApiResponses;

@RestController
public class DiscoveryController {
    private final DiscoveryService discovery;
    public DiscoveryController(DiscoveryService discovery) { this.discovery = discovery; }

    @GetMapping("/api/v1/public/reading/discovery")
    ResponseEntity<JsonNode> page(@RequestParam Map<String, String> query) { return ApiResponses.okNoStore(discovery.page(query)); }

    @GetMapping("/api/v1/admin/reading/integration")
    ResponseEntity<JsonNode> status() { return ApiResponses.okNoStore(discovery.status()); }
}
