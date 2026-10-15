package space.raychi.wellspring.reading;

import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ReadingSourceClient {
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Duration timeout;

    public ReadingSourceClient(@Value("${raychi.reading.request-timeout-ms:15000}") long timeoutMs) {
        timeout = Duration.ofMillis(Math.max(1000, Math.min(timeoutMs, 30000)));
    }

    public String fetch(ReadingModels.Source source) {
        URI uri = validate(source.adapter(), source.listingUrl());
        for (int redirects = 0; redirects < 4; redirects++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout)
                        .header("Accept", "text/html,application/xhtml+xml")
                        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                        .header("Accept-Language", "en-US,en;q=0.8").GET().build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    String location = response.headers().firstValue("location").orElseThrow();
                    uri = validate(source.adapter(), uri.resolve(location).toString());
                    continue;
                }
                if (response.statusCode() != 200) throw new SourceFailure("HTTP_" + response.statusCode());
                String type = response.headers().firstValue("content-type").orElse("").toLowerCase();
                if (!type.contains("text/html") && !type.contains("application/xhtml"))
                    throw new SourceFailure("UNSUPPORTED_CONTENT");
                try (InputStream input = response.body()) {
                    byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                    if (bytes.length > MAX_BYTES) throw new SourceFailure("SOURCE_TOO_LARGE");
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (SourceFailure ex) { throw ex; }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new SourceFailure("FETCH_INTERRUPTED");
            } catch (Exception ex) { throw new SourceFailure("FETCH_FAILED"); }
        }
        throw new SourceFailure("TOO_MANY_REDIRECTS");
    }

    static URI validate(String adapter, String value) {
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException ex) { throw new SourceFailure("INVALID_SOURCE_URL"); }
        String expectedHost = switch (adapter) {
            case "OPENAI_DEVELOPERS" -> "developers.openai.com";
            case "ANTHROPIC_ENGINEERING" -> "www.anthropic.com";
            default -> throw new SourceFailure("UNSUPPORTED_ADAPTER");
        };
        String prefix = adapter.equals("OPENAI_DEVELOPERS") ? "/blog" : "/engineering";
        if (!"https".equals(uri.getScheme()) || !expectedHost.equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() != -1
                || uri.getPath() == null
                || !(uri.getPath().equals(prefix) || uri.getPath().startsWith(prefix + "/")))
            throw new SourceFailure("INVALID_SOURCE_URL");
        return uri;
    }

    public static final class SourceFailure extends RuntimeException {
        private final String code;
        SourceFailure(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
