package com.changeimpact.auth.security;

import com.changeimpact.common.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JwtService Unit Tests")
class JwtServiceTest {

    private JwtService jwtService;
    private AppProperties appProperties;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getSecurity().getJwt().setSecret("this-is-a-very-long-secret-key-that-satisfies-the-256-bit-requirement-for-hmac-sha256");
        appProperties.getSecurity().getJwt().setAccessTokenExpiryMs(60_000L); // 1 min
        appProperties.getSecurity().getJwt().setRefreshTokenExpiryMs(120_000L); // 2 min

        jwtService = new JwtService(appProperties);
    }

    @Test
    @DisplayName("Should generate valid access token and extract claims accurately")
    void shouldGenerateAndValidateAccessToken() {
        UUID userId = UUID.randomUUID();
        String username = "devuser";
        String email = "dev@example.com";

        UserDetails userDetails = new User(email, "password", Collections.emptyList());

        String token = jwtService.generateAccessToken(userDetails, userId, username);
        assertNotNull(token);

        assertTrue(jwtService.isTokenValid(token, userDetails));
        assertEquals(email, jwtService.extractSubject(token));
        assertEquals(userId, jwtService.extractUserId(token));
        assertEquals(username, jwtService.extractClaim(token, "username"));
        assertEquals("access", jwtService.extractClaim(token, "type"));
    }

    @Test
    @DisplayName("Should generate refresh token with refresh claim type")
    void shouldGenerateRefreshToken() {
        String email = "refresh@example.com";
        String token = jwtService.generateRefreshToken(email);

        assertNotNull(token);
        assertEquals(email, jwtService.extractSubject(token));
        assertEquals("refresh", jwtService.extractClaim(token, "type"));
    }

    @Test
    @DisplayName("Should fail validation when token subject does not match userDetails")
    void shouldFailWhenSubjectDoesNotMatch() {
        UUID userId = UUID.randomUUID();
        UserDetails userDetails = new User("user1@example.com", "password", Collections.emptyList());
        UserDetails otherUser = new User("user2@example.com", "password", Collections.emptyList());

        String token = jwtService.generateAccessToken(userDetails, userId, "user1");
        assertFalse(jwtService.isTokenValid(token, otherUser));
    }
}

