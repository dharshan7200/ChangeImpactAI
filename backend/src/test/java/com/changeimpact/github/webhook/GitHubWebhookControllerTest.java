package com.changeimpact.github.webhook;

import com.changeimpact.common.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("Webhook endpoint: secret, signature, size, content type, de-duplication")
class GitHubWebhookControllerTest {

    private static final String SECRET = "s3cr3t";
    private final AppProperties props = new AppProperties();
    private final GitHubWebhookService service = mock(GitHubWebhookService.class);
    private GitHubWebhookController controller;

    @BeforeEach
    void setUp() {
        props.getGithub().setWebhookSecret(SECRET);
        controller = new GitHubWebhookController(props, service);
    }

    private MockHttpServletRequest request(String body, String contentType) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/webhooks/github");
        r.setContent(body.getBytes(StandardCharsets.UTF_8));
        r.setContentType(contentType);
        return r;
    }

    private String sign(String body) {
        return WebhookSignatureVerifier.sign(SECRET, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("503 without a configured secret: deliveries are never accepted unsigned")
    void notConfigured() throws Exception {
        props.getGithub().setWebhookSecret("");
        var res = controller.receive("ping", "d1", "sha256=00", request("{}", "application/json"));
        assertEquals(503, res.getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("401 for a missing or wrong signature; nothing is recorded")
    void badSignature() throws Exception {
        assertEquals(401, controller.receive("ping", "d1", null, request("{}", "application/json")).getStatusCode().value());
        assertEquals(401, controller.receive("ping", "d1", sign("{\"a\":1}"), request("{}", "application/json")).getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("413 for oversized payloads, 415 for form-encoded payloads, 400 for missing headers or invalid JSON")
    void rejectsMalformed() throws Exception {
        props.getGithub().setWebhookMaxBodyBytes(10);
        String big = "{\"x\":\"0123456789\"}";
        assertEquals(413, controller.receive("ping", "d1", sign(big), request(big, "application/json")).getStatusCode().value());
        props.getGithub().setWebhookMaxBodyBytes(1024);
        assertEquals(415, controller.receive("ping", "d1", sign("payload=%7B%7D"),
                request("payload=%7B%7D", "application/x-www-form-urlencoded")).getStatusCode().value());
        assertEquals(400, controller.receive(null, "d1", sign("{}"), request("{}", "application/json")).getStatusCode().value());
        assertEquals(400, controller.receive("ping", "bad id!", sign("{}"), request("{}", "application/json")).getStatusCode().value());
        assertEquals(400, controller.receive("ping", "d1", sign("{nope"), request("{nope", "application/json")).getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a valid delivery is registered once and processed; a redelivery is acknowledged without processing")
    void processesOnce() throws Exception {
        String body = "{\"zen\":\"Design for failure.\"}";
        when(service.register(eq("d-1"), eq("ping"), anyString())).thenReturn(true, false);
        when(service.process(eq("d-1"), eq("ping"), any())).thenReturn(new GitHubWebhookService.Outcome("ACCEPTED", "pong", List.of()));

        var first = controller.receive("ping", "d-1", sign(body), request(body, "application/json; charset=utf-8"));
        assertEquals(202, first.getStatusCode().value());
        assertEquals("ACCEPTED", first.getBody().get("status"));
        var second = controller.receive("ping", "d-1", sign(body), request(body, "application/json"));
        assertEquals(200, second.getStatusCode().value());
        assertEquals("DUPLICATE", second.getBody().get("status"));
        verify(service, times(1)).process(any(), any(), any());
        verify(service, times(2)).register(eq("d-1"), eq("ping"), eq(WebhookSignatureVerifier.sha256Hex(body.getBytes(StandardCharsets.UTF_8))));
    }
}
