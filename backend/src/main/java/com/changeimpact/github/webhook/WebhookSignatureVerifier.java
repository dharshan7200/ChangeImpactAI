package com.changeimpact.github.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies {@code X-Hub-Signature-256: sha256=<hex HMAC-SHA256(secret, raw body)>} in constant time,
 * as documented by GitHub. The raw request bytes must be used (no re-serialisation of the JSON).
 */
public final class WebhookSignatureVerifier {

    private WebhookSignatureVerifier() {}

    public static boolean isValid(String secret, byte[] body, String signatureHeader) {
        if (secret == null || secret.isEmpty() || signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        byte[] expected = sign(secret, body).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signatureHeader.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    /** {@code sha256=<hex>} for the given body. */
    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
