package space.raychi.wellspring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:analytics;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "raychi.admin.username=analytics-admin", "raychi.analytics.enabled=true",
        "raychi.analytics.token=private-test-token-not-for-browser",
        "raychi.analytics.origin=http://127.0.0.1:3000"
})
@AutoConfigureMockMvc
class AnalyticsFlowTest {
    private static final CopyOnWriteArrayList<String> payloads = new CopyOnWriteArrayList<>();
    private static final AtomicInteger reply = new AtomicInteger(202);
    private static final HttpServer upstream;
    static {
        try {
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            upstream.createContext("/v1/apps/raychi-public/events", exchange -> {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer private-test-token-not-for-browser");
                payloads.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(reply.get(), -1); exchange.close();
            });
            upstream.createContext("/v1/apps/raychi-public/report", exchange -> {
                String from = "2026-10-01", to = "2026-10-09";
                byte[] body = ("{\"appId\":\"raychi-public\",\"from\":\"" + from + "\",\"to\":\"" + to
                        + "\",\"pageViews\":2,\"unidentifiedPageViews\":1,\"visitorDays\":1,\"visits\":1,"
                        + "\"sources\":[],\"regions\":[],\"popularPaths\":[],\"trend\":[],"
                        + "\"retention\":{\"rawDays\":30,\"aggregateDays\":365,\"secret\":\"must-not-leak\"},"
                        + "\"definitions\":{\"visitors\":\"daily\",\"visits\":\"approximate\"},\"token\":\"must-not-leak\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            upstream.start();
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("raychi.analytics.url", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
        registry.add("raychi.admin.password-hash", () -> new BCryptPasswordEncoder().encode("analytics-password"));
    }
    @AfterAll static void stop() { upstream.stop(0); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String event(String path) throws Exception {
        return json.writeValueAsString(Map.of("id", UUID.randomUUID().toString(), "path", path,
                "occurredAt", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString()));
    }
    private MockHttpServletRequestBuilder collect(String body) {
        return post("/api/v1/public/analytics/events").with(csrf()).header("Origin", "http://127.0.0.1:3000")
                .header("User-Agent", "Mozilla/5.0 Firefox/147").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test void anonymousCollectorRequiresCsrfAndConfiguredOrigin() throws Exception {
        mvc.perform(post("/api/v1/public/analytics/events").contentType(MediaType.APPLICATION_JSON).content(event("/")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(collect(event("/")).header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/analytics/report?from=2026-10-01&to=2026-10-09"))
                .andExpect(status().isUnauthorized());
    }

    @Test void excludesOwnerBotDntAndGpcBeforeQueueing() throws Exception {
        int before = payloads.size();
        mvc.perform(collect(event("/")).with(user("owner"))).andExpect(status().isNoContent());
        mvc.perform(collect(event("/")).header("DNT", "1")).andExpect(status().isNoContent());
        mvc.perform(collect(event("/")).header("Sec-GPC", "1")).andExpect(status().isNoContent());
        mvc.perform(collect(event("/")).header("User-Agent", "Googlebot")).andExpect(status().isNoContent());
        assertThat(payloads.size()).isEqualTo(before);
    }

    @Test void rejectsPrivateUnknownAndQueryPathsAndCallerFlags() throws Exception {
        for (String path : new String[]{"/studio", "/search", "/writing?tag=secret", "/writing#private", "/writing/not-published", "/api/v1/auth/session"})
            mvc.perform(collect(event(path))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        for (String flag : new String[]{"owner", "bot", "ip", "type", "title"}) {
            var body = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(event("/")); body.put(flag, "forged");
            mvc.perform(collect(body.toString())).andExpect(status().isBadRequest());
        }
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(event("/")); body.put("source", "https://example.com/private?token=x");
        mvc.perform(collect(body.toString())).andExpect(status().isBadRequest());
    }

    @Test void retriesIdenticalPayloadWithoutBlockingPublicContent() throws Exception {
        reply.set(503);
        String body = event("/"); int start = payloads.size();
        try {
            mvc.perform(collect(body)).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("queued"));
            mvc.perform(get("/api/v1/public/settings")).andExpect(status().isOk());
            long end = System.currentTimeMillis() + 5000;
            while (payloads.size() < start + 2 && System.currentTimeMillis() < end) Thread.sleep(10);
            assertThat(payloads.size()).isGreaterThanOrEqualTo(start + 2);
            assertThat(payloads.get(start)).isEqualTo(payloads.get(start + 1));
            assertThat(json.readTree(payloads.get(start)).path("type").asText()).isEqualTo("page_view");
            assertThat(payloads.get(start)).doesNotContain("ip", "owner", "bot");
        } finally { reply.set(202); }
    }

    @Test void limitsPerSessionAndValidatesReportWithoutLeakingUpstreamFields() throws Exception {
        MockHttpSession session = new MockHttpSession();
        for (int i = 0; i < 60; i++) mvc.perform(collect(event("/")).session(session)).andExpect(status().isAccepted());
        mvc.perform(collect(event("/")).session(session)).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/v1/admin/analytics/report?from=2026-10-01&to=2026-10-09").with(user("owner")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.pageViews").value(2)).andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.retention.secret").doesNotExist());
        for (String query : new String[]{"from=x&to=2026-10-09", "from=2026-10-09&to=2026-10-01", "from=2025-01-01&to=2026-10-09", "from=2026-10-01&to=2026-10-09&ip=1.1.1.1", "from=2026-10-01&from=2026-10-02&to=2026-10-09"})
            mvc.perform(get("/api/v1/admin/analytics/report?" + query).with(user("owner"))).andExpect(status().isBadRequest());
    }
}
