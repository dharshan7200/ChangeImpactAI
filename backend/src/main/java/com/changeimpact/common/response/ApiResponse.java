package com.changeimpact.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Standardised API response envelope used by all controllers.
 *
 * <p>All API responses follow this structure:
 * <pre>
 * {
 *   "success": true,
 *   "message": "Operation completed",
 *   "data": { ... },
 *   "timestamp": "2024-01-01T00:00:00Z"
 * }
 * </pre>
 *
 * <p>Error responses include a machine-readable {@code code} and, for validation, an {@code errors} list:
 * <pre>
 * {
 *   "success": false,
 *   "code": "VALIDATION_FAILED",
 *   "message": "Validation failed",
 *   "errors": [{"field": "email", "message": "must be a valid email"}],
 *   "timestamp": "2024-01-01T00:00:00Z"
 * }
 * </pre>
 *
 * @param <T> the type of the response data payload
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final boolean success;
    /** Machine-readable error code on failures (e.g. {@code SAME_COMMIT}, {@code NOT_FOUND}); absent on success. */
    private final String code;
    private final String message;
    private final T data;
    private final List<ApiError> errors;
    private final Map<String, Object> metadata;

    @Builder.Default
    private final Instant timestamp = Instant.now();

    // ---- Factory methods ----

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> success(String message) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .build();
    }

    /** Error with a machine-readable code that clients can branch on. */
    public static <T> ApiResponse<T> error(String code, String message, List<ApiError> errors) {
        return ApiResponse.<T>builder()
                .success(false)
                .code(code)
                .message(message)
                .errors(errors)
                .build();
    }

    public static <T> ApiResponse<T> error(String message, List<ApiError> errors) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .errors(errors)
                .build();
    }

    /**
     * Paged response with metadata for the page info.
     */
    public static <T> ApiResponse<T> paged(T data, long totalElements, int page, int size) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .metadata(Map.of(
                        "totalElements", totalElements,
                        "page", page,
                        "size", size,
                        "totalPages", (int) Math.ceil((double) totalElements / size)
                ))
                .build();
    }

    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ApiError {
        private final String field;
        private final String message;
        private final String code;

        public static ApiError of(String field, String message) {
            return ApiError.builder().field(field).message(message).build();
        }

        public static ApiError of(String message) {
            return ApiError.builder().message(message).build();
        }
    }
}

