package space.raychi.wellspring.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import space.raychi.wellspring.api.ApiResponses;
import space.raychi.wellspring.dto.AnalyticsSettingsDto.Settings;

@RestController
public class AnalyticsController {
    private final AnalyticsService analytics;
    private final AnalyticsSettingsService settings;
    public AnalyticsController(AnalyticsService analytics, AnalyticsSettingsService settings) {
        this.analytics = analytics; this.settings = settings;
    }

    @GetMapping("/api/v1/admin/analytics/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Settings> settings() { return ApiResponses.okNoStore(settings.read()); }

    @PutMapping("/api/v1/admin/analytics/settings")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Settings> saveSettings(@RequestBody JsonNode input) {
        return ApiResponses.okNoStore(settings.save(input));
    }

    @PostMapping("/api/v1/public/analytics/events")
    public ResponseEntity<?> collect(@RequestBody JsonNode input, HttpServletRequest request) {
        var session = request.getSession();
        AnalyticsService.SessionBudget budget;
        synchronized (session) {
            budget = (AnalyticsService.SessionBudget) session.getAttribute("analytics.budget");
            if (budget == null) { budget = new AnalyticsService.SessionBudget(); session.setAttribute("analytics.budget", budget); }
        }
        var origins = java.util.Collections.list(request.getHeaders("Origin"));
        String result = analytics.collect(input, origins.size() == 1 ? origins.getFirst() : null,
                String.join(",", java.util.Collections.list(request.getHeaders("User-Agent"))),
                request.getUserPrincipal() != null || "1".equals(request.getHeader("DNT")) || "1".equals(request.getHeader("Sec-GPC")), budget, request.getRemoteAddr());
        return result == null ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
                : ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("status", result));
    }
    @GetMapping("/api/v1/admin/analytics/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(analytics.status());
    }
    @GetMapping("/api/v1/admin/analytics/report")
    public ResponseEntity<JsonNode> report(@RequestParam MultiValueMap<String, String> input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(analytics.report(input));
    }
    @GetMapping("/api/v1/admin/analytics/overview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<JsonNode> overview(@RequestParam MultiValueMap<String, String> input) {
        return ApiResponses.okNoStore(analytics.detailsReport(input, false));
    }
    @GetMapping("/api/v1/admin/analytics/events")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<JsonNode> events(@RequestParam MultiValueMap<String, String> input) {
        return ApiResponses.okNoStore(analytics.detailsReport(input, true));
    }
}
