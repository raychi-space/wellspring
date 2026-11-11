package space.raychi.wellspring.reading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import space.raychi.wellspring.api.ApiException;

/** Internal, read-only public-v1 client. No browser credentials or arbitrary upstream paths. */
@Component
public class AiHotClient {
    private static final int MAX_BYTES = 1_000_000;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json;
    private final String base;
    private final String managementToken;
    public final boolean enabled;

    public AiHotClient(ObjectMapper json, String base, boolean enabled) { this(json, base, enabled, ""); }

    @Autowired
    public AiHotClient(ObjectMapper json, @Value("${raychi.reading.aihot.url:http://127.0.0.1:18071}") String base,
            @Value("${raychi.reading.aihot.enabled:false}") boolean enabled,
            @Value("${raychi.reading.aihot.management-token:}") String managementToken) {
        URI uri = URI.create(base);
        if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !Set.of("", "/").contains(uri.getPath())) throw new IllegalStateException("Invalid AIHOT configuration");
        this.json = json; this.base = base.replaceAll("/$", ""); this.enabled = enabled;
        this.managementToken = managementToken;
    }

    public boolean managementConfigured() {
        return enabled && managementToken.matches("[A-Za-z0-9_-]{32,128}");
    }

    /** Fixed management operations only; writes are never automatically retried or redirected. */
    public JsonNode manage(String method, String path, JsonNode body) {
        if (!managementConfigured()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "READING_MANAGEMENT_UNCONFIGURED", "拾阅管理接口尚未配置。");
        String route = path.split("\\?", 2)[0];
        boolean allowed = switch (method) {
            case "GET" -> route.equals("/dashboard") || route.equals("/models") || route.equals("/sources")
                    || route.matches("/sources/[a-z0-9][a-z0-9-]{2,79}");
            case "POST" -> route.equals("/sources") || route.matches("/sources/[a-z0-9][a-z0-9-]{2,79}/(check|collect)");
            case "PUT" -> route.matches("/sources/[a-z0-9][a-z0-9-]{2,79}")
                    || route.matches("/(models|budgets)/[A-Za-z][A-Za-z0-9_-]{0,99}");
            default -> false;
        };
        if (!allowed || path.contains("#") || path.contains("\\")) throw new IllegalArgumentException("Invalid management operation");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/api/internal/raychi-management/v1" + path))
                .timeout(Duration.ofSeconds(8)).header("Accept", "application/json")
                .header("Authorization", "Bearer " + managementToken);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        var future = http.sendAsync(builder.build(), ignored -> new LimitedBody());
        try {
            var response = future.get(8, TimeUnit.SECONDS);
            if (!response.headers().firstValue("content-type").orElse("").startsWith("application/json")) throw new Unavailable();
            JsonNode value = json.readTree(response.body());
            int status = response.statusCode();
            if (status >= 200 && status < 300 && value != null && value.path("schemaVersion").asInt(-1) == 1) return value;
            if (status == 400) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请检查填写的拾阅配置。");
            if (status == 404) throw new ApiException(HttpStatus.NOT_FOUND, "READING_SOURCE_NOT_FOUND", "没有这个RSS来源。");
            if (status == 409) {
                String code = value == null ? "" : value.path("code").asText();
                String message = switch (code) {
                    case "SOURCE_DUPLICATE" -> "该RSS来源已存在。";
                    case "SOURCE_PAUSED" -> "请先启用该来源。";
                    case "MODEL_CONFIGURATION_STALE" -> "请等待Worker同步模型配置后重试。";
                    default -> "配置已被修改，请刷新后重试。";
                };
                throw new ApiException(HttpStatus.CONFLICT, "READING_CONFIGURATION_CONFLICT", message);
            }
            throw new Unavailable();
        } catch (ApiException ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw managementUnavailable(); }
        catch (Exception ex) { throw managementUnavailable(); }
        finally { if (!future.isDone()) future.cancel(true); }
    }

    private static ApiException managementUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "READING_MANAGEMENT_UNAVAILABLE", "暂时无法获取拾阅管理结果，请刷新后核对。");
    }

    public JsonNode get(String path, long deadline) {
        if (!enabled || !path.startsWith("/api/v1/")) throw new Unavailable();
        for (int redirects = 0; redirects < 4; redirects++) {
            long remaining = Math.min(1800, deadline - System.currentTimeMillis());
            if (remaining < 1) throw new Unavailable();
            var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMillis(remaining))
                    .header("Accept", "application/json").GET().build();
            var future = http.sendAsync(request, ignored -> new LimitedBody());
            try {
                var response = future.get(remaining, TimeUnit.MILLISECONDS);
                if (response.statusCode() == 308) {
                    String location = response.headers().firstValue("location").orElse("");
                    if (!path.matches("/api/v1/stories/[0-9a-fA-F-]{36}")
                            || !location.matches("/api/v1/stories/[0-9a-fA-F-]{36}")) throw new Unavailable();
                    path = location; continue;
                }
                if (response.statusCode() == 404) return null;
                if (response.statusCode() == 400) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "拾阅查询条件已失效，请返回第一页重试。");
                if (response.statusCode() != 200 || !response.headers().firstValue("content-type").orElse("").startsWith("application/json"))
                    throw new Unavailable();
                JsonNode result = json.readTree(response.body());
                if (result == null || result.path("schemaVersion").asInt(-1) != 1) throw new Unavailable();
                return result;
            } catch (ApiException ex) { throw ex; } catch (InterruptedException ex) {
                Thread.currentThread().interrupt(); throw new Unavailable();
            } catch (Exception ex) { throw new Unavailable(); }
            finally { if (!future.isDone()) future.cancel(true); }
        }
        throw new Unavailable();
    }

    static final class Unavailable extends RuntimeException {
        Unavailable() { super("Reading service unavailable"); }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > MAX_BYTES) {
                    subscription.cancel(); result.completeExceptionally(new Unavailable()); return;
                }
                byte[] value = new byte[buffer.remaining()]; buffer.get(value); bytes.writeBytes(value);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(new Unavailable()); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
