package space.raychi.wellspring.api;

import java.util.List;

public record ApiErrorResponse(String code, String message, String requestId, List<FieldError> fieldErrors) {
    public record FieldError(String field, String message) {}
}
