package com.changeimpact.common.exception;

import com.changeimpact.common.response.ApiResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Global exception handler for all REST controllers.
 *
 * <p>Centralises error handling and ensures:
 * <ul>
 *   <li>Consistent {@link ApiResponse} envelope for all errors</li>
 *   <li>No stack traces exposed to clients</li>
 *   <li>Meaningful error messages without leaking internal details</li>
 *   <li>Structured logging for all unexpected errors</li>
 * </ul>
 *
 * <p>Security note: Generic 500 errors return only "An internal error occurred"
 * to avoid leaking implementation details to potential attackers.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ---- Validation errors ----

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        List<ApiResponse.ApiError> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toApiError)
                .collect(Collectors.toList());

        log.debug("Request validation failed: {} errors", errors.size());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("VALIDATION_FAILED", "Validation failed", errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolations(
            ConstraintViolationException ex) {

        List<ApiResponse.ApiError> errors = ex.getConstraintViolations()
                .stream()
                .map(this::toApiError)
                .collect(Collectors.toList());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("CONSTRAINT_VIOLATION", "Constraint violation", errors));
    }

    // ---- Business and domain errors ----

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        log.debug("Business exception [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getErrorCode(), ex.getMessage(), null));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ResourceNotFoundException ex) {
        log.debug("Resource not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("NOT_FOUND", ex.getMessage(), null));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.debug("Access denied: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("ACCESS_DENIED", ex.getMessage(), null));
    }

    // ---- Authentication errors ----

    /** Spring Security method security (@PreAuthorize) denials: 403, not a 500. */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleSecurityAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("ACCESS_DENIED", "You do not have permission to perform this action", null));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        // Generic message prevents username enumeration
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("INVALID_CREDENTIALS", "Invalid credentials", null));
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiResponse<Void>> handleDisabled(DisabledException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("ACCOUNT_DISABLED", "Account is disabled. Please contact support.", null));
    }

    @ExceptionHandler(LockedException.class)
    public ResponseEntity<ApiResponse<Void>> handleLocked(LockedException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("ACCOUNT_LOCKED", "Account is locked. Please contact support.", null));
    }

    // ---- GitHub integration errors ----

    @ExceptionHandler(GitHubIntegrationException.class)
    public ResponseEntity<ApiResponse<Void>> handleGitHubError(GitHubIntegrationException ex) {
        log.warn("GitHub integration error [status={}]: {}", ex.getStatusCode(), ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error("GITHUB_ERROR", "GitHub integration error: " + ex.getMessage(), null));
    }

    @ExceptionHandler(IngestionException.class)
    public ResponseEntity<ApiResponse<Void>> handleIngestionError(IngestionException ex) {
        log.error("Ingestion error: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INGESTION_FAILED", "Repository ingestion failed: " + ex.getMessage(), null));
    }

    // ---- AI provider errors (optional Gemini features) ----

    @ExceptionHandler(com.changeimpact.ai.GeminiClient.AiUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleAiUnavailable(com.changeimpact.ai.GeminiClient.AiUnavailableException ex) {
        log.warn("AI provider unavailable: {}", ex.getMessage());
        if (ex instanceof com.changeimpact.ai.GeminiClient.AiQuotaExceededException) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.error("AI_QUOTA_EXCEEDED", ex.getMessage(), null));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("AI_UNAVAILABLE", "The AI model is unavailable right now (Gemini overloaded or unreachable, "
                        + "including the fallback model). Try again in a few minutes; the deterministic analysis is not affected.", null));
    }

    // ---- HTTP errors ----

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableMessage(HttpMessageNotReadableException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("MALFORMED_REQUEST", "Malformed or unreadable request body", null));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("MISSING_PARAMETER", "Required parameter missing: " + ex.getParameterName(), null));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("INVALID_PARAMETER", "Invalid parameter type for: " + ex.getName(), null));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("NOT_FOUND", "Endpoint not found", null));
    }

    // ---- Catch-all ----

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        // Log full details server-side, never expose to client
        log.error("Unexpected error processing request", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "An internal error occurred. Please try again later.", null));
    }

    // ---- Helpers ----

    private ApiResponse.ApiError toApiError(FieldError error) {
        return ApiResponse.ApiError.builder()
                .field(error.getField())
                .message(error.getDefaultMessage())
                .build();
    }

    private ApiResponse.ApiError toApiError(ConstraintViolation<?> violation) {
        String field = violation.getPropertyPath().toString();
        return ApiResponse.ApiError.builder()
                .field(field)
                .message(violation.getMessage())
                .build();
    }
}

