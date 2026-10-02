package space.raychi.wellspring.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    public static ApiErrorResponse body(HttpServletRequest request, String code, String message) {
        return new ApiErrorResponse(code, message, RequestIdFilter.requestId(request), List.of());
    }

    private static ResponseEntity<ApiErrorResponse> error(HttpStatus status, HttpServletRequest request,
                                                         String code, String message) {
        return ApiResponses.error(status, body(request, code, message));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> api(ApiException ex, HttpServletRequest request) {
        if (ex.status().is5xxServerError()) log.error("Request {} failed", RequestIdFilter.requestId(request), ex);
        return error(ex.status(), request, ex.code(), ex.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> conflict(DataIntegrityViolationException ex, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, request, "DATA_CONFLICT", "数据冲突，请刷新后重试。");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiErrorResponse.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(field -> new ApiErrorResponse.FieldError(field.getField(), field.getDefaultMessage())).toList();
        return ApiResponses.error(HttpStatus.BAD_REQUEST,
                new ApiErrorResponse("VALIDATION_FAILED", "请检查输入内容。", RequestIdFilter.requestId(request), fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    ResponseEntity<ApiErrorResponse> invalidRequest(Exception ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, request, "VALIDATION_FAILED", "请求参数或 JSON 格式无效。");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiErrorResponse> tooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, request, "ASSET_TOO_LARGE", "图片超过大小限制。");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> method(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        ResponseEntity<ApiErrorResponse> response = error(HttpStatus.METHOD_NOT_ALLOWED, request,
                "METHOD_NOT_ALLOWED", "请求方法不受支持。");
        return ResponseEntity.status(response.getStatusCode()).headers(ex.getHeaders())
                .headers(response.getHeaders()).body(response.getBody());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> mediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, request, "MEDIA_TYPE_NOT_SUPPORTED", "请求内容类型不受支持。");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ApiErrorResponse> accept(HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        return error(HttpStatus.NOT_ACCEPTABLE, request, "MEDIA_TYPE_NOT_ACCEPTABLE", "请求的响应类型不受支持。");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> missing(NoResourceFoundException ex, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, request, "RESOURCE_NOT_FOUND", "请求资源不存在。");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> forbidden(AccessDeniedException ex, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, request, "FORBIDDEN", "没有权限执行此操作。");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Request {} failed", RequestIdFilter.requestId(request), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, request, "INTERNAL_ERROR", "服务暂时不可用，请稍后重试。");
    }
}
