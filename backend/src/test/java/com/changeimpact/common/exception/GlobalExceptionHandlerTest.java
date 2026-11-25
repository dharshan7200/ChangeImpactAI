package com.changeimpact.common.exception;

import com.changeimpact.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Error responses carry the machine-readable code the API documentation promises (found missing in verification). */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessErrorsExposeTheirCode() {
        ResponseEntity<ApiResponse<Void>> r = handler.handleBusinessException(
                new BusinessException("SAME_COMMIT", "Both refs resolve to the same commit"));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody().getCode()).isEqualTo("SAME_COMMIT");
        assertThat(r.getBody().getMessage()).isEqualTo("Both refs resolve to the same commit");
        assertThat(r.getBody().isSuccess()).isFalse();
    }

    @Test
    void otherErrorsHaveStableCodes() {
        assertThat(handler.handleResourceNotFound(new ResourceNotFoundException("Repository", "x")).getBody().getCode()).isEqualTo("NOT_FOUND");
        assertThat(handler.handleBadCredentials(new BadCredentialsException("x")).getBody().getCode()).isEqualTo("INVALID_CREDENTIALS");
        var overloaded = handler.handleAiUnavailable(new com.changeimpact.ai.GeminiClient.AiUnavailableException("Gemini m answered 503: {...}"));
        assertThat(overloaded.getStatusCode().value()).isEqualTo(503);
        assertThat(overloaded.getBody().getCode()).isEqualTo("AI_UNAVAILABLE");
        var quota = handler.handleAiUnavailable(new com.changeimpact.ai.GeminiClient.AiQuotaExceededException("The daily Gemini quota ..."));
        assertThat(quota.getStatusCode().value()).isEqualTo(429);
        assertThat(quota.getBody().getCode()).isEqualTo("AI_QUOTA_EXCEEDED");
    }

    @Test
    void codeIsSerialisedAndOmittedOnSuccess() {
        JsonMapper json = JsonMapper.builder().build();
        String error = json.writeValueAsString(handler.handleBusinessException(new BusinessException("INVALID_REPORT", "bad")).getBody());
        assertThat(error).contains("\"code\":\"INVALID_REPORT\"");
        assertThat(json.writeValueAsString(ApiResponse.success("ok"))).doesNotContain("\"code\"");
    }
}
