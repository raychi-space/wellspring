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
                var response = http.send(request("/events").header("Content-Type", "application/json")
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
