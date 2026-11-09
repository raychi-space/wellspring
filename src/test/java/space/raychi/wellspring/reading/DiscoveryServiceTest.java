package space.raychi.wellspring.reading;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import space.raychi.wellspring.api.ApiException;

class DiscoveryServiceTest {
    private HttpServer upstream;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger hits = new AtomicInteger();
    private String body;
    private DiscoveryService service;
    private MutableClock clock;
    private static final String ID = "e2b281f6-08b7-4867-9501-dcfdfd35299a";

    @BeforeEach void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
            catch (java.io.IOException ignored) { /* Oversize subscriber cancels the socket. */ }
        }); upstream.start();
        clock = new MutableClock();
        service = new DiscoveryService(new AiHotClient(json, "http://127.0.0.1:" + upstream.getAddress().getPort(), true), json, clock);
    }
    @AfterEach void stop() { upstream.stop(0); }

    @Test void latestWhitelistsFieldsAndUrlsPreservesTextAndCursor() throws Exception {
        body = """
            {"schemaVersion":1,"secret":"do-not-expose","items":[
              {"id":"sample1","title":"<script>alert(1)</script>","summary":"中文摘要","source":{"name":"Source"},
               "publishedAt":"2026-11-09T01:00:00Z","links":{"original":"https://example.org/original","aihot":"http://internal/items/sample1"}},
              {"id":"sample2","title":"Unsafe URL","links":{"original":"javascript:alert(1)"},"source":{"name":"Bad"}}
            ],"page":{"nextCursor":"v1.abc_123"}}
            """;
        var page = service.page(Map.of("view", "latest"));
        assertTrue(page.path("available").asBoolean());
        assertEquals("<script>alert(1)</script>", page.path("items").get(0).path("title").asText());
        assertEquals("https://example.org/original", page.path("items").get(0).path("sources").get(0).path("url").asText());
        assertEquals(0, page.path("items").get(1).path("sources").size());
        assertEquals("v1.abc_123", page.path("nextCursor").asText());
        assertEquals("2026-11-09T01:00:00Z", page.path("updatedAt").asText());
        assertFalse(page.toString().contains("do-not-expose")); assertFalse(page.toString().contains("internal"));
        assertEquals(page, service.page(Map.of("view", "latest"))); assertEquals(1, hits.get());
    }

    @Test void hotUsesVersionedStoryDigestAndPublicSources() {
        body = """
            {"schemaVersion":1,"items":[{"id":"sample1","title":"事件标题","sourceCount":2,
              "source":{"name":"Source"},"latestAt":"2026-11-09T01:00:00Z",
              "links":{"story":"http://internal/story/ID","original":"https://example.org/original"}}],
             "story":{"digest":"跨来源归并摘要","reports":[
                {"title":"报道一","source":{"name":"来源一"},"links":{"original":"https://example.org/one"}},
                {"title":"报道二","source":{"name":"来源二"},"links":{"original":"https://example.net/two"}}]}}
            """.replace("ID", ID);
        var entry = service.page(Map.of()).path("items").get(0);
        assertEquals(ID, entry.path("id").asText()); assertEquals("跨来源归并摘要", entry.path("summary").asText());
        assertEquals(2, entry.path("sources").size()); assertEquals(2, hits.get());
    }

    @Test void allReportPeriodsAndEmptyIssueAreNormalized() {
        for (var period : new String[]{"daily", "weekly", "monthly"}) {
            String field = switch(period) { case "daily" -> "date"; case "weekly" -> "week"; default -> "month"; };
            String key = switch(period) { case "daily" -> "2026-11-09"; case "weekly" -> "2026-W46"; default -> "2026-11"; };
            body = """
              {"schemaVersion":1,"items":[{"FIELD":"KEY","generatedAt":"2026-11-09T02:00:00Z"}],
               "report":{"FIELD":"KEY","generatedAt":"2026-11-09T02:00:00Z",
               "lead":{"title":"日报头条","leadParagraph":"摘要"},"headline":"周期头条","overview":"综述",
               "sections":[{"label":"消息","items":[{"title":"公开报道","summary":"公开摘要","source":{"name":"来源"},"links":{"original":"https://example.org/report"}}]}]}}
              """.replace("FIELD", field).replace("KEY", key);
            var page = service.page(Map.of("view", "reports", "period", period));
            assertEquals(key, page.path("issues").get(0).path("key").asText());
            assertEquals(key, page.path("report").path("key").asText());
            assertEquals("公开摘要", page.path("report").path("sections").get(0).path("items").get(0).path("summary").asText());
        }
        status.set(404);
        // An absent index is an outage; an empty, valid index followed by absent issue is covered by HTTP E2E.
        assertFalse(service.page(Map.of("view", "reports", "key", "2026-11-08")).path("available").asBoolean());
    }

    @Test void lastGoodIsBoundedAndFailuresNeverExposeUpstreamBody() {
        body = "{\"schemaVersion\":1,\"items\":[]}";
        var first = service.page(Map.of()); assertTrue(first.path("available").asBoolean());
        clock.advance(61); status.set(503); body = "{\"secret\":\"upstream credentials\"}";
        var stale = service.page(Map.of()); assertTrue(stale.path("stale").asBoolean());
        clock.advance(900);
        var unavailable = service.page(Map.of()); assertFalse(unavailable.path("available").asBoolean());
        for (String key : new String[]{"items", "issues", "report", "nextCursor", "updatedAt"}) assertTrue(unavailable.has(key));
        assertFalse(unavailable.toString().contains("credentials"));
    }

    @Test void queryValidationAndExpiredCursorDoNotMasqueradeAsEmptyData() {
        for (Map<String,String> query : java.util.List.of(Map.of("url", "http://localhost"), Map.of("view", "unknown"),
                Map.of("view", "hot", "cursor", "v1.cursor"), Map.of("view", "reports", "key", "2026-02-30"),
                Map.of("view", "reports", "period", "weekly", "key", "2025-W53"),
                Map.of("view", "reports", "period", "monthly", "key", "2026-13")))
            assertThrows(ApiException.class, () -> service.page(query));
        assertEquals(0, hits.get());
        status.set(400); body = "{\"code\":\"invalid_cursor\"}";
        assertThrows(ApiException.class, () -> service.page(Map.of("view", "latest", "cursor", "v1.invalid")));
    }

    @Test void oversizeAndInvalidSchemasFailClosed() {
        body = " ".repeat(1_000_001);
        assertFalse(service.page(Map.of()).path("available").asBoolean());
        body = "{\"schemaVersion\":2,\"items\":[]}";
        assertFalse(service.page(Map.of("view", "latest")).path("available").asBoolean());
        for (String url : new String[]{"", "javascript:alert(1)", "http://127.0.0.1/path", "http://postgres/path", "https://user:pass@example.org"})
            assertNull(DiscoveryService.safeUrl(url));
    }

    @Test void statusUsesLatestPublishedContentEvenBeforeHotGrouping() {
        body = """
            {"schemaVersion":1,"items":[{"id":"sample1","title":"新内容",
              "publishedAt":"2026-11-09T03:00:00Z","links":{}}],"page":{"nextCursor":null}}
            """;
        var state = service.status();
        assertTrue(state.path("connected").asBoolean());
        assertEquals("2026-11-09T03:00:00Z", state.path("updatedAt").asText());
    }

    private static class MutableClock extends Clock {
        private Instant now = Instant.now();
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
