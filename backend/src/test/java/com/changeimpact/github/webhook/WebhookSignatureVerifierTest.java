package com.changeimpact.github.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Webhook HMAC-SHA256 signature verification")
class WebhookSignatureVerifierTest {

    // Example from GitHub's documentation "Validating webhook deliveries"
    private static final String SECRET = "It's a Secret to Everybody";
    private static final byte[] BODY = "Hello, World!".getBytes(StandardCharsets.UTF_8);
    private static final String SIGNATURE = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

    @Test
    @DisplayName("matches GitHub's documented test vector")
    void documentedVector() {
        assertEquals(SIGNATURE, WebhookSignatureVerifier.sign(SECRET, BODY));
        assertTrue(WebhookSignatureVerifier.isValid(SECRET, BODY, SIGNATURE));
    }

    @Test
    @DisplayName("rejects a wrong secret, a modified body, a missing or malformed header and an empty secret")
    void rejects() {
        assertFalse(WebhookSignatureVerifier.isValid("another secret", BODY, SIGNATURE));
        assertFalse(WebhookSignatureVerifier.isValid(SECRET, "Hello, World?".getBytes(StandardCharsets.UTF_8), SIGNATURE));
        assertFalse(WebhookSignatureVerifier.isValid(SECRET, BODY, null));
        assertFalse(WebhookSignatureVerifier.isValid(SECRET, BODY, SIGNATURE.substring(7)), "sha256= prefix required");
        assertFalse(WebhookSignatureVerifier.isValid(SECRET, BODY, "sha1=" + SIGNATURE.substring(7)));
        assertFalse(WebhookSignatureVerifier.isValid("", BODY, SIGNATURE));
        assertFalse(WebhookSignatureVerifier.isValid(null, BODY, SIGNATURE));
    }

    @Test
    @DisplayName("hex case of the header does not matter")
    void caseInsensitiveHex() {
        assertTrue(WebhookSignatureVerifier.isValid(SECRET, BODY, "sha256=" + SIGNATURE.substring(7).toUpperCase()));
    }
}
