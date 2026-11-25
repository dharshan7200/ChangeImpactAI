package com.changeimpact.github.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption service for sensitive values such as GitHub access tokens.
 *
 * <p>AES-GCM provides both confidentiality and integrity (authenticated encryption).
 * A fresh 96-bit IV is generated for every encryption operation. The IV is prepended
 * to the ciphertext so it can be recovered during decryption.
 *
 * <p>Format of encrypted value (Base64-encoded):
 * <pre>
 *   [IV (12 bytes)] [GCM tag (16 bytes appended by JCA)] [ciphertext]
 * </pre>
 *
 * <p>The encryption key must be a 32-byte (256-bit) Base64-encoded string
 * provided via the {@code TOKEN_ENCRYPTION_KEY} environment variable.
 *
 * <p><strong>Security warning</strong>: Never log plaintext tokens or the
 * encryption key. Rotate the key using a re-encryption job if compromised.
 */
@Service
public class TokenEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    /** Public development default; the prod profile refuses to start with it (ProductionSafetyCheck). */
    public static final String DEV_DEFAULT_KEY = "DV3Paxg2A3dr4hl62xaNGxwG+gv38pPQiOEuvBOLcdI=";

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenEncryptionService(
            @Value("${TOKEN_ENCRYPTION_KEY:" + DEV_DEFAULT_KEY + "}") String keyBase64) {
        byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "TOKEN_ENCRYPTION_KEY must be a Base64-encoded 32-byte (256-bit) key. " +
                    "Generate with: openssl rand -base64 32"
            );
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Encrypts a plaintext value.
     *
     * @param plaintext the value to encrypt; must not be null
     * @return Base64-encoded encrypted value including IV
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(IV_LENGTH_BYTES + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new RuntimeException("Token encryption failed", e);
        }
    }

    /**
     * Decrypts an encrypted value produced by {@link #encrypt(String)}.
     *
     * @param encryptedBase64 the Base64-encoded encrypted value
     * @return the decrypted plaintext
     */
    public String decrypt(String encryptedBase64) {
        if (encryptedBase64 == null) return null;
        try {
            byte[] data = Base64.getDecoder().decode(encryptedBase64);

            ByteBuffer buffer = ByteBuffer.wrap(data);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);

            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Token decryption failed", e);
        }
    }
}

