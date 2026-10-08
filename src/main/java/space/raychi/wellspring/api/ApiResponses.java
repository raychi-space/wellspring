package space.raychi.wellspring.api;

import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Builds HTTP responses while preserving the versioned API payloads. */
public final class ApiResponses {
    private ApiResponses() {}

    public static <T> ResponseEntity<T> ok(T data) { return ResponseEntity.ok(data); }

    public static <T> ResponseEntity<T> okNoStore(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data);
    }

    public static <T> ResponseEntity<T> created(String location, T data) {
        return ResponseEntity.created(URI.create(location)).body(data);
    }

    public static <T> ResponseEntity<T> created(T data) {
        return ResponseEntity.status(201).body(data);
    }

    public static ResponseEntity<ApiErrorResponse> error(HttpStatus status, ApiErrorResponse error) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(error);
    }

    public static ResponseEntity<Void> noContent() { return ResponseEntity.noContent().build(); }

    public static ResponseEntity<byte[]> binary(byte[] bytes, String mediaType, boolean head) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(mediaType)).contentLength(bytes.length)
                .body(head ? null : bytes);
    }
}
