package space.raychi.wellspring;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiErrors {
    public record ErrorBody(String code, String message, String requestId, List<Object> fieldErrors) {}

    public static ErrorBody body(String code, String message) {
        return new ErrorBody(code, message, UUID.randomUUID().toString(), List.of());
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> api(ApiException ex) {
        return ResponseEntity.status(ex.status()).body(body(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ErrorBody> duplicate(DuplicateKeyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("SLUG_CONFLICT", "地址别名已存在。"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> invalid(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(body("VALIDATION_FAILED", "请检查输入内容。"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorBody> tooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body("ASSET_TOO_LARGE", "图片超过大小限制。"));
    }
}
