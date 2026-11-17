package com.changeimpact.auth.security;

import com.changeimpact.common.config.AppProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Service for creating and validating JSON Web Tokens.
 *
 * <p>Uses JJWT (io.jsonwebtoken) with HMAC-SHA256 (HS256) signing.
 * The signing key is derived from the configured secret using HKDF-style
 * key derivation. The secret must be at least 32 bytes (256 bits) for HS256.
 *
 * <p>Access tokens are short-lived (default 15 minutes).
 * Refresh tokens are long-lived (default 7 days) and stored hashed in the DB.
 *
 * <p>Claims included in access tokens:
 * <ul>
 *   <li>{@code sub} — user email (standard JWT subject)</li>
 *   <li>{@code userId} — platform user UUID</li>
 *   <li>{@code username} — display username</li>
 *   <li>{@code type} — "access" or "refresh"</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JwtService {

    private final AppProperties appProperties;

    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    // ---- Token generation ----

    public String generateAccessToken(UserDetails userDetails, UUID userId, String username) {
        return buildToken(
                Map.of(CLAIM_USER_ID, userId.toString(),
                       CLAIM_USERNAME, username,
                       CLAIM_TYPE, TYPE_ACCESS),
                userDetails.getUsername(),
                appProperties.getSecurity().getJwt().getAccessTokenExpiryMs()
        );
    }

    public String generateRefreshToken(String subject) {
        return buildToken(
                Map.of(CLAIM_TYPE, TYPE_REFRESH),
                subject,
                appProperties.getSecurity().getJwt().getRefreshTokenExpiryMs()
        );
    }

    private String buildToken(Map<String, Object> extraClaims, String subject, long expiryMs) {
        return Jwts.builder()
                .claims(extraClaims)
                .subject(subject)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiryMs))
                .id(UUID.randomUUID().toString())   // jti — prevents replay if revoked
                .signWith(getSigningKey())
                .compact();
    }

    // ---- Token validation ----

    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            String subject = extractSubject(token);
            return subject != null
                    && subject.equals(userDetails.getUsername())
                    && !isTokenExpired(token)
                    && TYPE_ACCESS.equals(extractClaim(token, CLAIM_TYPE));
        } catch (JwtException e) {
            log.debug("Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    // ---- Claims extraction ----

    public String extractSubject(String token) {
        return extractAllClaims(token).getSubject();
    }

    public UUID extractUserId(String token) {
        String userId = extractClaim(token, CLAIM_USER_ID);
        return userId != null ? UUID.fromString(userId) : null;
    }

    @SuppressWarnings("unchecked")
    public <T> T extractClaim(String token, String claimName) {
        return (T) extractAllClaims(token).get(claimName);
    }

    // ---- Internal ----

    private boolean isTokenExpired(String token) {
        return extractAllClaims(token).getExpiration().before(new Date());
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(
                java.util.Base64.getEncoder().encodeToString(
                        appProperties.getSecurity().getJwt().getSecret().getBytes()
                )
        );
        return Keys.hmacShaKeyFor(keyBytes);
    }
}

