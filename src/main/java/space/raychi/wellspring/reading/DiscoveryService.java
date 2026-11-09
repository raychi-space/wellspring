package space.raychi.wellspring.reading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import space.raychi.wellspring.api.ApiException;

/** Site-owned DTOs and bounded last-good cache; AIHOT remains the content authority. */
@Service
public class DiscoveryService {
    private record Cached(ObjectNode body, long at) {}
    private final Map<String, Cached> cache = new LinkedHashMap<>(64, .75f, true);
    private final AiHotClient client;
    private final ObjectMapper json;
    private final Clock clock;
    private Instant checkedAt, successAt, updatedAt;
    @Autowired
    public DiscoveryService(AiHotClient client, ObjectMapper json) { this(client, json, Clock.systemUTC()); }
    DiscoveryService(AiHotClient client, ObjectMapper json, Clock clock) { this.client = client; this.json = json; this.clock = clock; }

    public synchronized ObjectNode page(Map<String, String> query) {
        String view = query.getOrDefault("view", "hot"), period = query.getOrDefault("period", "daily");
        String key = query.getOrDefault("key", "latest"), cursor = query.getOrDefault("cursor", "");
        Set<String> keys = switch (view) {
            case "hot" -> Set.of("view"); case "latest" -> Set.of("view", "cursor");
            case "reports" -> Set.of("view", "period", "key"); default -> throw invalid();
        };
        if (!keys.containsAll(query.keySet()) || !Set.of("daily", "weekly", "monthly").contains(period)
                || cursor.length() > 4096 || (!cursor.isEmpty() && !cursor.matches("[A-Za-z0-9_.~-]+"))) throw invalid();
        if (view.equals("reports")) validateKey(period, key);
        var page = json.createObjectNode().put("mode", client.enabled ? "aihot" : "legacy").put("view", view)
                .put("period", period).put("key", key).put("available", true).put("stale", false);
        page.putArray("items"); page.putArray("issues"); page.putNull("report"); page.putNull("nextCursor"); page.putNull("updatedAt");
        if (!client.enabled) return page;
        String cacheKey = view + ":" + period + ":" + key + ":" + cursor;
        long now = clock.millis(); Cached saved = cache.get(cacheKey);
        if (saved != null && now - saved.at < 60_000) return saved.body.deepCopy();
        checkedAt = Instant.now(clock);
        try {
            long deadline = System.currentTimeMillis() + 4000;
            switch (view) {
                case "hot" -> hot(page, deadline);
                case "latest" -> latest(page, cursor, deadline);
                case "reports" -> reports(page, period, key, deadline);
                default -> throw invalid();
            }
            successAt = Instant.now(clock);
            Instant newest = null;
            for (JsonNode row : page.path("items")) newest = later(newest, row.path("publishedAt"));
            newest = later(newest, page.path("report").path("generatedAt"));
            time(page, "updatedAt", newest); updatedAt = newest;
            cache.put(cacheKey, new Cached(page.deepCopy(), now));
            if (cache.size() > 64) cache.remove(cache.keySet().iterator().next());
            return page;
        } catch (AiHotClient.Unavailable | IllegalStateException ex) {
            if (saved != null && now - saved.at < 900_000) return saved.body.deepCopy().put("stale", true);
            page.removeAll().put("mode", "aihot").put("view", view).put("period", period).put("key", key)
                    .put("available", false).put("stale", false).putNull("updatedAt").putNull("nextCursor").putNull("report");
            page.putArray("items"); page.putArray("issues"); return page;
        }
    }

    public synchronized ObjectNode status() {
        boolean connected = false;
        if (client.enabled) {
            checkedAt = Instant.now(clock);
            try {
                Instant newest = null;
                for (JsonNode row : array(client.get("/api/v1/items?mode=all&window=7d&by=timeline&limit=1", System.currentTimeMillis() + 1800), "items")) {
                    JsonNode published = row.path("publishedAt");
                    newest = later(newest, published.isNull() || published.isMissingNode() ? row.path("discoveredAt") : published);
                }
                updatedAt = newest; connected = true; successAt = checkedAt;
            }
            catch (AiHotClient.Unavailable | IllegalStateException ignored) { /* Public, safe state only. */ }
        }
        ObjectNode state = json.createObjectNode().put("mode", client.enabled ? "aihot" : "legacy")
                .put("configured", client.enabled).put("connected", connected).put("legacyActive", !client.enabled);
        time(state, "checkedAt", checkedAt); time(state, "lastSuccessAt", successAt); time(state, "updatedAt", updatedAt);
        return state;
    }

    private void hot(ObjectNode page, long deadline) {
        ArrayNode out = (ArrayNode) page.get("items"); int count = 0;
        for (JsonNode item : array(client.get("/api/v1/hot-topics", deadline), "items")) {
            if (++count > 20) break;
            String storyId = storyId(item.path("links").path("story").asText());
            ObjectNode row = entry(item, count); row.put("sourceCount", Math.max(1, Math.min(10000, item.path("sourceCount").asInt(1))));
            row.put("publishedAt", instant(item.path("latestAt")));
            if (storyId != null) {
                row.put("id", storyId);
                try {
                    JsonNode response = client.get("/api/v1/stories/" + storyId, deadline);
                    if (response == null) continue; // Withdrawn content must not be resurrected by a ranking.
                    JsonNode story = response.path("story");
                    row.put("summary", text(story, "digest", 16000, true));
                    ArrayNode sources = json.createArrayNode();
                    for (JsonNode report : array(story, "reports")) {
                        if (sources.size() >= 50) break;
                        source(sources, report);
                    }
                    row.set("sources", sources);
                } catch (AiHotClient.Unavailable ignored) { /* Ranking remains useful while optional summaries recover. */ }
            }
            out.add(row);
        }
    }

    private void latest(ObjectNode page, String cursor, long deadline) {
        String path = "/api/v1/items?mode=all&window=7d&by=timeline&limit=30";
        if (!cursor.isEmpty()) path += "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        JsonNode response = client.get(path, deadline);
        if (response == null) throw new AiHotClient.Unavailable();
        ArrayNode rows = (ArrayNode) page.get("items"); int count = 0;
        for (JsonNode item : array(response, "items")) { if (++count > 30) break; rows.add(entry(item, count)); }
        JsonNode next = response.path("page").path("nextCursor");
        if (!next.isNull() && !next.isMissingNode()) {
            String value = next.asText();
            if (value.length() > 4096 || !value.matches("[A-Za-z0-9_.~-]+")) throw new IllegalStateException();
            page.put("nextCursor", value);
        }
    }

    private void reports(ObjectNode page, String period, String key, long deadline) {
        String route = switch (period) { case "daily" -> "dailies"; case "weekly" -> "weeklies"; default -> "monthlies"; };
        int count = 0;
        for (JsonNode issue : array(client.get("/api/v1/" + route + "?limit=30", deadline), "items")) {
            if (++count > 30) break;
            String value = text(issue, switch (period) { case "daily" -> "date"; case "weekly" -> "week"; default -> "month"; }, 12, false);
            upstreamKey(period, value);
            ((ArrayNode) page.get("issues")).add(json.createObjectNode().put("key", value).put("generatedAt", instant(issue.path("generatedAt"))));
        }
        JsonNode response = client.get("/api/v1/" + route + "/" + key, deadline);
        if (response == null) return;
        JsonNode data = response.path("report");
        String reportKey = upstreamKey(period, text(data, switch (period) { case "daily" -> "date"; case "weekly" -> "week"; default -> "month"; }, 12, false));
        if (!key.equals("latest") && !key.equals(reportKey)) throw new IllegalStateException();
        var report = json.createObjectNode().put("key", reportKey)
                .put("title", text(period.equals("daily") ? data.path("lead") : data, period.equals("daily") ? "title" : "headline", 1000, true))
                .put("intro", text(period.equals("daily") ? data.path("lead") : data, period.equals("daily") ? "leadParagraph" : "overview", 16000, true))
                .put("generatedAt", instant(data.path("generatedAt")));
        ArrayNode sections = report.putArray("sections"); int total = 0;
        for (JsonNode section : array(data, "sections")) {
            if (sections.size() >= 30) break;
            ObjectNode group = json.createObjectNode().put("label", text(section, "label", 200, false))
                    .put("summary", text(section, "summary", 8000, true));
            ArrayNode entries = group.putArray("items");
            for (JsonNode item : array(section, "items")) { if (++total > 150) break; entries.add(entry(item, total)); }
            sections.add(group);
        }
        if (data.path("flashes").isArray() && !data.path("flashes").isEmpty()) {
            ObjectNode flashes = json.createObjectNode().put("label", "简讯"); ArrayNode entries = flashes.putArray("items");
            for (JsonNode item : data.path("flashes")) { if (++total > 150) break; entries.add(entry(item, total)); }
            sections.add(flashes);
        }
        page.set("report", report);
    }

    private ObjectNode entry(JsonNode item, int position) {
        String id = item.path("id").asText("item-" + position);
        if (!id.matches("[a-zA-Z0-9_-]{1,128}")) throw new IllegalStateException();
        ObjectNode row = json.createObjectNode().put("id", id).put("title", text(item, "title", 1000, false))
                .put("summary", text(item, "summary", 16000, true)).put("sourceCount", 1);
        row.put("publishedAt", instant(item.path("publishedAt").isNull() || item.path("publishedAt").isMissingNode() ? item.path("discoveredAt") : item.path("publishedAt")));
        source(row.putArray("sources"), item); return row;
    }
    private void source(ArrayNode sources, JsonNode item) {
        String url = safeUrl(item.path("links").path("original").asText());
        if (url != null && !url.equals(item.path("links").path("story").asText())
                && !url.equals(item.path("links").path("aihot").asText())) sources.add(json.createObjectNode().put("name", text(item.path("source"), "name", 200, true))
                .put("title", text(item, "title", 1000, false)).put("url", url));
    }
    static String safeUrl(String value) {
        try {
            URI uri = URI.create(value); String host = uri.getHost();
            if (value.length() > 3000 || uri.getScheme() == null || !Set.of("https", "http").contains(uri.getScheme()) || host == null
                    || uri.getUserInfo() != null || !host.contains(".") || host.endsWith(".local")
                    || host.matches("(?i)(localhost|127\\..*|10\\..*|192\\.168\\..*|169\\.254\\..*|172\\.(1[6-9]|2[0-9]|3[01])\\..*|0\\..*)")) return null;
            return uri.toASCIIString();
        } catch (IllegalArgumentException ex) { return null; }
    }
    private static String storyId(String value) {
        try { String path = URI.create(value).getPath(); String id = path.substring(path.lastIndexOf('/') + 1); return id.matches("[0-9a-fA-F-]{36}") ? UUID.fromString(id).toString() : null; }
        catch (IllegalArgumentException | NullPointerException ex) { return null; }
    }
    private static JsonNode array(JsonNode node, String name) {
        if (node == null || !node.path(name).isArray() || node.path(name).size() > 1000) throw new IllegalStateException();
        return node.path(name);
    }
    private static String text(JsonNode node, String key, int max, boolean nullable) {
        JsonNode value = node.path(key);
        if (nullable && (value.isNull() || value.isMissingNode())) return null;
        if (!value.isTextual() || value.asText().length() > max) throw new IllegalStateException();
        return value.asText();
    }
    private static String instant(JsonNode value) {
        if (value.isNull() || value.isMissingNode()) return null;
        try { return Instant.parse(value.asText()).toString(); } catch (DateTimeException ex) { throw new IllegalStateException(); }
    }
    private static Instant later(Instant before, JsonNode value) {
        String parsed = instant(value); if (parsed == null) return before;
        Instant next = Instant.parse(parsed); return before == null || next.isAfter(before) ? next : before;
    }
    private static void time(ObjectNode out, String key, Instant value) { if (value == null) out.putNull(key); else out.put(key, value.toString()); }
    private static void validateKey(String period, String key) {
        if (key.equals("latest")) return;
        try {
            if (period.equals("daily") && key.matches("20\\d{2}-\\d{2}-\\d{2}")) { LocalDate.parse(key); return; }
            if (period.equals("monthly") && key.matches("20\\d{2}-\\d{2}")) { YearMonth.parse(key); return; }
            if (period.equals("weekly") && key.matches("20\\d{2}-W\\d{2}")) {
                int year = Integer.parseInt(key.substring(0, 4)), week = Integer.parseInt(key.substring(6));
                LocalDate date = LocalDate.of(year, 1, 4).with(WeekFields.ISO.weekOfWeekBasedYear(), week);
                if (date.get(WeekFields.ISO.weekBasedYear()) == year && date.get(WeekFields.ISO.weekOfWeekBasedYear()) == week) return;
            }
        } catch (DateTimeException | NumberFormatException ignored) { }
        throw invalid();
    }
    private static String upstreamKey(String period, String key) {
        try { validateKey(period, key); return key; }
        catch (ApiException ex) { throw new IllegalStateException(); }
    }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "拾阅查询参数无效。"); }
}
