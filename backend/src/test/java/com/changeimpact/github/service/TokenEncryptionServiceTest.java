package com.changeimpact.github.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TokenEncryptionService Unit Tests")
class TokenEncryptionServiceTest {

    private TokenEncryptionService encryptionService;
    // Standard 32-byte Base64 key for test
    private static final String VALID_KEY_BASE64 = Base64.getEncoder().encodeToString(
            "12345678901234567890123456789012".getBytes()
    );

    @BeforeEach
    void setUp() {
        encryptionService = new TokenEncryptionService(VALID_KEY_BASE64);
    }

    @Test
    @DisplayName("Should successfully encrypt and decrypt a plaintext token")
    void shouldEncryptAndDecryptToken() {
        String token = "ghp_1234567890abcdefghijklmnopqrstuvwxyz";

        String encrypted = encryptionService.encrypt(token);
        assertNotNull(encrypted);
        assertNotEquals(token, encrypted);

        String decrypted = encryptionService.decrypt(encrypted);
        assertEquals(token, decrypted);
    }

    @Test
    @DisplayName("Should produce different ciphertexts for identical plaintext due to random IV")
    void shouldProduceDifferentCiphertexts() {
        String token = "ghp_secure_github_pat_12345";

        String encrypted1 = encryptionService.encrypt(token);
        String encrypted2 = encryptionService.encrypt(token);

        assertNotEquals(encrypted1, encrypted2, "AES-GCM with fresh IVs must yield distinct ciphertexts");
        assertEquals(token, encryptionService.decrypt(encrypted1));
        assertEquals(token, encryptionService.decrypt(encrypted2));
    }

    @Test
    @DisplayName("Should return null when encrypting or decrypting null")
    void shouldHandleNullGracefully() {
        assertNull(encryptionService.encrypt(null));
        assertNull(encryptionService.decrypt(null));
    }

    @Test
    @DisplayName("Should reject invalid key lengths on construction")
    void shouldRejectInvalidKeyLength() {
        String invalidKey = Base64.getEncoder().encodeToString("too-short".getBytes());
        assertThrows(IllegalArgumentException.class, () -> new TokenEncryptionService(invalidKey));
    }
}

