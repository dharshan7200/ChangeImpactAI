package com.changeimpact.github.webhook;

import com.changeimpact.common.config.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * GitHub App webhook receiver. Public (no JWT) but every delivery must carry a valid
 * {@code X-Hub-Signature-256} computed with the configured webhook secret over the raw body.
 *
 * <ul>
 *   <li>503 — webhook secret not configured (deliveries are never accepted unsigned)</li>
 *   <li>401 — missing or invalid signature</li>
 *   <li>413 — body larger than {@code app.github.webhook-max-body-bytes}</li>
 *   <li>415 — not {@code application/json} (configure the App's webhook content type as JSON)</li>
 *   <li>200 — duplicate delivery (same X-GitHub-Delivery), not processed again</li>
 *   <li>202 — accepted / ignored, with the processing outcome</li>
 *   <li>500 — processing failed; recorded as FAILED, so a redelivery from GitHub is processed again</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "GitHub Webhooks", description = "GitHub App webhook endpoint (HMAC-SHA256 signed)")
public class GitHubWebhookController {

    private static final Pattern DELIVERY_ID = Pattern.compile("^[A-Za-z0-9-]{1,100}$");
    private static final Pattern EVENT = Pattern.compile("^[a-z_]{1,50}$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AppProperties appProperties;
    private final GitHubWebhookService webhookService;

    @PostMapping("/github")
    @Operation(summary = "Receive a GitHub App webhook delivery (signature-verified)")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            HttpServletRequest request) throws IOException {

        String secret = appProperties.getGithub().getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            return reply(HttpStatus.SERVICE_UNAVAILABLE, "NOT_CONFIGURED", "GitHub webhook secret is not configured");
        }
        // before reading the body: form-encoded bodies are parsed (consumed) by the servlet container
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            return reply(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_CONTENT_TYPE",
                    "Set the GitHub App webhook content type to application/json");
        }
        int max = appProperties.getGithub().getWebhookMaxBodyBytes();
        if (request.getContentLengthLong() > max) {
            return reply(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE", "Payload exceeds " + max + " bytes");
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(max + 1);
        }
        if (body.length > max) {
            return reply(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE", "Payload exceeds " + max + " bytes");
        }
        if (!WebhookSignatureVerifier.isValid(secret, body, signature)) {
            log.warn("Rejected webhook delivery {} with missing or invalid signature", sanitize(deliveryId));
            com.changeimpact.common.metrics.PlatformMetrics.webhook(event, "REJECTED");
            return reply(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Missing or invalid X-Hub-Signature-256");
        }
        if (event == null || !EVENT.matcher(event).matches() || deliveryId == null || !DELIVERY_ID.matcher(deliveryId).matches()) {
            return reply(HttpStatus.BAD_REQUEST, "INVALID_HEADERS", "X-GitHub-Event and X-GitHub-Delivery are required");
        }
        JsonNode payload;
        try {
            payload = JSON.readTree(body);
        } catch (Exception e) {
            return reply(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Body is not valid JSON");
        }
        if (!webhookService.register(deliveryId, event, WebhookSignatureVerifier.sha256Hex(body))) {
            com.changeimpact.common.metrics.PlatformMetrics.webhook(event, "DUPLICATE");
            return reply(HttpStatus.OK, "DUPLICATE", "Delivery " + deliveryId + " was already received");
        }
        GitHubWebhookService.Outcome outcome = webhookService.process(deliveryId, event, payload);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", outcome.status());
        response.put("detail", outcome.detail());
        response.put("analysisIds", outcome.analysisIds());
        return ResponseEntity.status("FAILED".equals(outcome.status()) ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.ACCEPTED)
                .body(response);
    }

    private static ResponseEntity<Map<String, Object>> reply(HttpStatus status, String code, String detail) {
        return ResponseEntity.status(status).body(Map.of("status", code, "detail", detail));
    }

    private static String sanitize(String v) {
        return v == null ? "-" : v.replaceAll("[^A-Za-z0-9-]", "").substring(0, Math.min(100, v.replaceAll("[^A-Za-z0-9-]", "").length()));
    }
}
