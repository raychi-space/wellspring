package space.raychi.wellspring.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AnalyticsClient {
    enum V2Resource {
        REPORT("report"), EVENTS("events"), MAP("map");

        final String path;

        V2Resource(String path) { this.path = path; }
    }

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(1);
    private static final int MAX_RESPONSE_CHARACTERS = 250_000;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    private final ObjectMapper json;
    private final String base, app, token;
    public final boolean enabled;

    public AnalyticsClient(ObjectMapper json,
            @Value("${raychi.analytics.url}") String base,
            @Value("${raychi.analytics.app}") String app,
            @Value("${raychi.analytics.token}") String token,
            @Value("${raychi.analytics.enabled}") boolean enabled) {
        this.json = json; this.app = app; this.token = token; this.enabled = enabled;
        URI uri = URI.create(base);
        if (!app.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}")
                || !Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !Set.of("", "/").contains(uri.getPath()) || (enabled && token.length() < 16))
            throw new IllegalStateException("Invalid analytics configuration");
        this.base = base.replaceAll("/$", "");
    }

    /** Identical payload and ID on retry; no upstream body or credentials in exceptions/logs. */
    public boolean collect(JsonNode event) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                var response = http.send(request("v2", "/events").header("Content-Type", "application/json")
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

    private HttpRequest.Builder request(String version, String suffix) {
        return HttpRequest.newBuilder(URI.create(base + "/" + version + "/apps/" + app + suffix))
                .timeout(REQUEST_TIMEOUT).header("Authorization", "Bearer " + token);
    }

    private JsonNode readReport(String version, String resource, String query, String from, String to)
            throws IOException, InterruptedException {
        var response = http.send(request(version, "/" + resource + "?" + query).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || response.body().length() > MAX_RESPONSE_CHARACTERS)
            throw new IllegalStateException();
        JsonNode data = json.readTree(response.body());
        if (data == null || !data.isObject() || !app.equals(data.path("appId").asText())
                || !from.equals(data.path("from").asText()) || !to.equals(data.path("to").asText()))
            throw new IllegalStateException();
        return data;
    }

    JsonNode details(String query, String from, String to, V2Resource resource) {
        try {
            JsonNode data = readReport("v2", resource.path, query, from, to);
            var safe = json.createObjectNode();
            for (String key : new String[]{"appId", "from", "to"}) safe.set(key, data.get(key));
            if (resource == V2Resource.MAP) {
                copyMap(data, safe, from, to);
                return safe;
            }
            if (resource == V2Resource.EVENTS) {
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

    private void copyMap(JsonNode data, ObjectNode safe, String from, String to) {
        copyNumber(data, safe, "uniqueIps");
        copyNumber(data, safe, "unlocatedIps");
        copyMapLevel(data, safe, "detailLevel");
        JsonNode coverage = data.path("coverage");
        if (coverage.isNull()) {
            safe.putNull("coverage");
        } else {
            var range = safe.putObject("coverage");
            copyText(coverage, range, "from");
            copyText(coverage, range, "to");
            LocalDate start = LocalDate.parse(range.path("from").asText());
            LocalDate end = LocalDate.parse(range.path("to").asText());
            if (start.isBefore(LocalDate.parse(from)) || end.isAfter(LocalDate.parse(to)) || !start.isBefore(end))
                throw new IllegalStateException();
        }
        long uniqueIps = safe.path("uniqueIps").asLong();
        long counted = safe.path("unlocatedIps").asLong();
        if (counted > uniqueIps || (coverage.isNull() && uniqueIps != 0)
                || !data.path("points").isArray() || data.path("points").size() > 500)
            throw new IllegalStateException();
        var points = safe.putArray("points");
        var ids = new HashSet<String>();
        for (JsonNode item : data.path("points")) {
            var point = points.addObject();
            copyText(item, point, "id");
            String id = point.path("id").asText();
            if (!id.matches("[0-9]{1,16}") || !ids.add(id)) throw new IllegalStateException();
            copyMapLevel(item, point, "level");
            copyText(item, point, "country");
            for (String key : new String[]{"province", "city"}) {
                if (item.path(key).isNull()) point.putNull(key); else copyText(item, point, key);
            }
            copyCoordinate(item, point, "latitude", 90);
            copyCoordinate(item, point, "longitude", 180);
            copyNumber(item, point, "uniqueIps");
            copyNumber(item, point, "pageViews");
            long groupSize = point.path("uniqueIps").asLong();
            if (groupSize == 0 || groupSize > uniqueIps - counted || point.path("pageViews").asLong() < groupSize)
                throw new IllegalStateException();
            counted += groupSize;
        }
        if (counted != uniqueIps) throw new IllegalStateException();
    }

    private void copyMapLevel(JsonNode input, ObjectNode output, String key) {
        copyText(input, output, key);
        if (!Set.of("city", "province", "country").contains(output.path(key).asText()))
            throw new IllegalStateException();
    }

    private void copyCoordinate(JsonNode input, ObjectNode output, String key, int limit) {
        JsonNode coordinate = input.path(key);
        if (!coordinate.isNumber() || !Double.isFinite(coordinate.asDouble()) || Math.abs(coordinate.asDouble()) > limit)
            throw new IllegalStateException();
        output.set(key, coordinate);
    }

    private void copyNumber(JsonNode input, ObjectNode output, String key) {
        if (!input.path(key).isIntegralNumber() || !input.path(key).canConvertToLong() || input.path(key).asLong() < 0) throw new IllegalStateException();
        output.set(key, input.get(key));
    }
    private void copyText(JsonNode input, ObjectNode output, String key) {
        if (!input.path(key).isTextual() || input.path(key).asText().length() > 512) throw new IllegalStateException();
        output.set(key, input.get(key));
    }

    public JsonNode report(String query, String from, String to) {
        try {
            JsonNode data = readReport("v1", "report", query, from, to);
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
