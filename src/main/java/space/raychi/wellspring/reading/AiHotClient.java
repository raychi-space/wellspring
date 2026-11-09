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
    public final boolean enabled;

    public AiHotClient(ObjectMapper json, @Value("${raychi.reading.aihot.url:http://127.0.0.1:18071}") String base,
            @Value("${raychi.reading.aihot.enabled:false}") boolean enabled) {
        URI uri = URI.create(base);
        if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !Set.of("", "/").contains(uri.getPath())) throw new IllegalStateException("Invalid AIHOT configuration");
        this.json = json; this.base = base.replaceAll("/$", ""); this.enabled = enabled;
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
