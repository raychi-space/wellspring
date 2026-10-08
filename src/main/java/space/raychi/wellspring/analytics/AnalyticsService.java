package space.raychi.wellspring.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.service.ArticleService;

@Service
public class AnalyticsService {
    public static final class SessionBudget {
        private long minute = -1;
        private int count;
        synchronized boolean accept() {
            long now = System.currentTimeMillis() / 60_000;
            if (minute != now) { minute = now; count = 0; }
            return ++count <= 60;
        }
    }
    private final AnalyticsClient client;
    private final ArticleService articles;
    private final String origin;
    private final AtomicLong delivered = new AtomicLong(), dropped = new AtomicLong();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256), task -> {
                Thread thread = new Thread(task, "analytics-collector"); thread.setDaemon(true); return thread;
            });

    public AnalyticsService(AnalyticsClient client, ArticleService articles,
            @Value("${raychi.analytics.origin:http://127.0.0.1:3000}") String origin) {
        this.client = client; this.articles = articles; this.origin = origin;
        URI uri = URI.create(origin);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !uri.getPath().isEmpty()) throw new IllegalStateException("Invalid analytics origin");
    }

    public String collect(JsonNode input, String requestOrigin, String userAgent, boolean excluded, SessionBudget budget) {
        if (!origin.equals(requestOrigin)) throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "请求来源无效。");
        if (!client.enabled || excluded || userAgent == null || userAgent.isBlank()
                || userAgent.toLowerCase(Locale.ROOT).matches(".*(bot|crawl|spider|slurp|headless|preview).*")) return null;
        if (!budget.accept()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ANALYTICS_RATE_LIMITED", "采集请求过于频繁。");
        if (input == null || !input.isObject()) throw bad();
        var names = input.fieldNames();
        while (names.hasNext()) if (!Set.of("id", "path", "occurredAt", "visitorId", "source").contains(names.next())) throw bad();
        uuid(text(input, "id", 36, true));
        String path = text(input, "path", 160, true);
        path(path, true);
        String occurred = text(input, "occurredAt", 40, true);
        try {
            if (!occurred.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?Z")) throw bad();
            Instant time = java.time.OffsetDateTime.parse(occurred).toInstant(), now = Instant.now();
            if (time.isBefore(now.minusSeconds(1800)) || time.isAfter(now.plusSeconds(300))) throw bad();
        } catch (java.time.DateTimeException ex) { throw bad(); }
        String visitor = text(input, "visitorId", 36, false);
        if (visitor != null) uuid(visitor);
        String source = text(input, "source", 253, false);
        if (source != null) source(source);
        ObjectNode event = ((ObjectNode) input).deepCopy(); event.put("type", "page_view");
        try {
            worker.execute(() -> { if (client.collect(event)) delivered.incrementAndGet(); else dropped.incrementAndGet(); });
            return "queued";
        } catch (RejectedExecutionException ex) { dropped.incrementAndGet(); return "dropped"; }
    }

    private static String text(JsonNode input, String key, int max, boolean required) {
        if (!input.has(key)) { if (required) throw bad(); return null; }
        JsonNode value = input.get(key);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max) throw bad();
        return value.asText();
    }
    private static void uuid(String value) {
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw bad();
    }
    private static void source(String value) {
        if (value == null || !value.matches("(?=.{1,253}$)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) throw bad();
        // Hostnames only; numeric IP literals are never accepted as source identity.
        if (value.matches("[0-9.]+")) throw bad();
    }
    private void path(String value, boolean published) {
        if (value == null) throw bad();
        if (Set.of("/", "/writing", "/posts", "/archive", "/more").contains(value)) return;
        if (!value.matches("/(writing|posts)/[a-z0-9]+(?:-[a-z0-9]+)*") || value.length() > 160) throw bad();
        if (published) {
            String[] parts = value.split("/");
            try { articles.getPublic(parts[1].equals("writing") ? "ARTICLE" : "POST", parts[2]); }
            catch (ApiException ex) { throw bad(); }
        }
    }

    public Map<String, Object> status() {
        return Map.of("enabled", client.enabled, "queued", worker.getQueue().size(),
                "delivered", delivered.get(), "dropped", dropped.get());
    }
    public JsonNode report(MultiValueMap<String, String> input) {
        for (String key : input.keySet())
            if (!Set.of("from", "to", "path", "source").contains(key) || input.get(key).size() != 1) throw bad();
        String from = input.getFirst("from"), to = input.getFirst("to");
        try {
            if (from == null || to == null || !from.matches("\\d{4}-\\d{2}-\\d{2}") || !to.matches("\\d{4}-\\d{2}-\\d{2}")) throw bad();
            long days = ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to));
            if (days < 1 || days > 366) throw bad();
        } catch (java.time.DateTimeException ex) { throw bad(); }
        if (input.containsKey("path")) path(input.getFirst("path"), false);
        if (input.containsKey("source") && !"direct_or_unknown".equals(input.getFirst("source"))) source(input.getFirst("source"));
        if (!client.enabled) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ANALYTICS_DISABLED", "访问统计尚未启用。");
        String query = input.entrySet().stream().map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue().getFirst(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
        try { return client.report(query, from, to); }
        catch (IllegalStateException ex) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ANALYTICS_UNAVAILABLE", "访问统计暂时不可用，请稍后重试。"); }
    }
    private static ApiException bad() { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "统计参数无效。"); }
    @PreDestroy void stop() { worker.shutdownNow(); }
}
