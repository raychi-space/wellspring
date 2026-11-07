package space.raychi.wellspring.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AnalyticsClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final ObjectMapper json;
    private final String base, app, token;
    public final boolean enabled;

    public AnalyticsClient(ObjectMapper json,
            @Value("${raychi.analytics.url:http://127.0.0.1:8093}") String base,
            @Value("${raychi.analytics.app:raychi-public}") String app,
            @Value("${raychi.analytics.token:}") String token,
            @Value("${raychi.analytics.enabled:false}") boolean enabled) {
        this.json = json; this.app = app; this.token = token; this.enabled = enabled;
        URI uri = URI.create(base);
        if (!app.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}")
                || !Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !Set.of("", "/").contains(uri.getPath()) || (enabled && token.length() < 16))
            throw new IllegalStateException("Invalid analytics configuration");
        this.base = base.replaceAll("/$", "") + "/v1/apps/" + app;
    }

    /** Identical payload and ID on retry; no upstream body or credentials in exceptions/logs. */
    public boolean collect(JsonNode event) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                var response = http.send(requestV2("/events").header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(event))).build(),
                        HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();
                if (status == 200 || status == 202) return true;
                if (status < 500 && status != 429) return false;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt(); return false;
            } catch (Exception ex) { /* best effort; one bounded retry */ }
        }
        return false;
    }

    private HttpRequest.Builder request(String suffix) {
        return HttpRequest.newBuilder(URI.create(base + suffix)).timeout(Duration.ofSeconds(1))
                .header("Authorization", "Bearer " + token);
    }
    private HttpRequest.Builder requestV2(String suffix) {
        return HttpRequest.newBuilder(URI.create(base.replace("/v1/apps/", "/v2/apps/") + suffix)).timeout(Duration.ofSeconds(1))
                .header("Authorization", "Bearer " + token);
    }

    public JsonNode details(String query, String from, String to, boolean events) {
        try {
            var response = http.send(requestV2((events ? "/events?" : "/report?") + query).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 250_000) throw new IllegalStateException();
            JsonNode data = json.readTree(response.body());
            if (!data.isObject() || !app.equals(data.path("appId").asText()) || !from.equals(data.path("from").asText()) || !to.equals(data.path("to").asText())) throw new IllegalStateException();
            var safe = json.createObjectNode();
            for (String key : new String[]{"appId", "from", "to"}) safe.set(key, data.get(key));
            if (events) {
                for (String key : new String[]{"total", "page", "pageSize"}) copyNumber(data, safe, key);
                if (!data.path("items").isArray() || data.path("items").size() > 100) throw new IllegalStateException();
                var items = safe.putArray("items");
                for (JsonNode item : data.path("items")) {
                    var row = items.addObject();
                    for (String key : new String[]{"id", "occurredAt", "path", "source", "country", "province", "city"}) copyText(item, row, key);
                    if (item.path("ip").isNull()) row.putNull("ip"); else copyText(item, row, "ip");
                }
            } else {
                var summary = safe.putObject("summary");
                for (String key : new String[]{"pageViews", "visitors", "todayPageViews", "todayVisitors"}) copyNumber(data.path("summary"), summary, key);
                for (String key : new String[]{"trend", "countries", "provinces", "cities", "sources", "popularPaths"}) {
                    if (!data.path(key).isArray() || data.path(key).size() > 366) throw new IllegalStateException();
                    var rows = safe.putArray(key);
                    for (JsonNode item : data.path(key)) {
                        var row = rows.addObject(); copyText(item, row, key.equals("trend") ? "day" : "name"); copyNumber(item, row, "pageViews");
                    }
                }
            }
            var retention = safe.putObject("retention");
            for (String key : new String[]{"rawDays", "aggregateDays"}) copyNumber(data.path("retention"), retention, key);
            for (String key : new String[]{"timeZone", "visitorTrackingSince"}) copyText(data.path("retention"), retention, key);
            if (!"Asia/Shanghai".equals(retention.path("timeZone").asText())) throw new IllegalStateException();
            java.time.Instant.parse(retention.path("visitorTrackingSince").asText());
            if (!data.path("retention").path("geoConfigured").isBoolean()) throw new IllegalStateException();
            retention.set("geoConfigured", data.path("retention").get("geoConfigured"));
            return safe;
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("Analytics unavailable"); }
        catch (Exception ex) { throw new IllegalStateException("Analytics unavailable"); }
    }
    private void copyNumber(JsonNode input, com.fasterxml.jackson.databind.node.ObjectNode output, String key) {
        if (!input.path(key).isIntegralNumber() || !input.path(key).canConvertToLong() || input.path(key).asLong() < 0) throw new IllegalStateException();
        output.set(key, input.get(key));
    }
    private void copyText(JsonNode input, com.fasterxml.jackson.databind.node.ObjectNode output, String key) {
        if (!input.path(key).isTextual() || input.path(key).asText().length() > 512) throw new IllegalStateException();
        output.set(key, input.get(key));
    }

    public JsonNode report(String query, String from, String to) {
        try {
            var response = http.send(request("/report?" + query).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 250_000) throw new IllegalStateException();
            JsonNode data = json.readTree(response.body());
            if (!data.isObject() || !app.equals(data.path("appId").asText())
                    || !from.equals(data.path("from").asText()) || !to.equals(data.path("to").asText()))
                throw new IllegalStateException();
            for (String key : new String[]{"pageViews", "unidentifiedPageViews", "visitorDays", "visits"})
                if (!data.path(key).isIntegralNumber() || data.path(key).asLong() < 0) throw new IllegalStateException();
            for (String key : new String[]{"trend", "sources", "regions", "popularPaths"}) {
                if (!data.path(key).isArray() || data.path(key).size() > 366) throw new IllegalStateException();
                for (JsonNode item : data.path(key)) {
                    if (!item.path(key.equals("trend") ? "day" : "name").isTextual()
                            || !item.path("pageViews").isIntegralNumber() || item.path("pageViews").asLong() < 0)
                        throw new IllegalStateException();
                }
            }
            if (!data.path("retention").path("rawDays").isIntegralNumber()
                    || !data.path("retention").path("aggregateDays").isIntegralNumber()
                    || !data.path("definitions").path("visitors").isTextual()
                    || !data.path("definitions").path("visits").isTextual()) throw new IllegalStateException();
            // Return only contractual aggregate fields, never unexpected private upstream fields.
            var safe = json.createObjectNode();
            for (String key : new String[]{"appId", "from", "to", "pageViews", "unidentifiedPageViews", "visitorDays", "visits"})
                safe.set(key, data.get(key));
            for (String key : new String[]{"trend", "sources", "regions", "popularPaths"}) {
                var rows = safe.putArray(key);
                for (JsonNode item : data.path(key)) {
                    String name = key.equals("trend") ? "day" : "name";
                    var row = rows.addObject(); row.set(name, item.get(name)); row.set("pageViews", item.get("pageViews"));
                }
            }
            var retention = safe.putObject("retention");
            retention.set("rawDays", data.path("retention").get("rawDays"));
            retention.set("aggregateDays", data.path("retention").get("aggregateDays"));
            var definitions = safe.putObject("definitions");
            definitions.set("visitors", data.path("definitions").get("visitors"));
            definitions.set("visits", data.path("definitions").get("visits"));
            return safe;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Analytics unavailable");
        } catch (Exception ex) { throw new IllegalStateException("Analytics unavailable"); }
    }
}
