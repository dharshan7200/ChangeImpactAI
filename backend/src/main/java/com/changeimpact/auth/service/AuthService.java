package com.changeimpact.auth.service;

import com.changeimpact.auth.dto.*;
import com.changeimpact.auth.security.JwtService;
import com.changeimpact.common.config.AppProperties;
import com.changeimpact.common.exception.BusinessException;
import com.changeimpact.common.exception.ResourceNotFoundException;
import com.changeimpact.repository.entity.RefreshToken;
import com.changeimpact.repository.entity.Role;
import com.changeimpact.repository.entity.User;
import com.changeimpact.repository.repository.RefreshTokenRepository;
import com.changeimpact.repository.repository.RoleRepository;
import com.changeimpact.repository.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Authentication service handling registration, login, and token management.
 *
 * <p>Security decisions:
 * <ul>
 *   <li>Refresh tokens are stored as SHA-256 hashes — plaintext never persists</li>
 *   <li>Login errors return a generic "Invalid credentials" to prevent enumeration</li>
 *   <li>BCrypt is used for password hashing (strength configured per environment)</li>
 *   <li>Expired/revoked refresh tokens are cleaned up on a schedule</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final AppProperties appProperties;

    // ---- Registration ----

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException("DUPLICATE_EMAIL",
                    "An account with this email already exists");
        }
        if (userRepository.existsByUsername(request.username())) {
            throw new BusinessException("DUPLICATE_USERNAME",
                    "This username is already taken");
        }

        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseThrow(() -> new IllegalStateException("ROLE_USER not found — run migrations"));

        User user = User.builder()
                .email(request.email().toLowerCase().strip())
                .username(request.username().strip())
                .passwordHash(passwordEncoder.encode(request.password()))
                .displayName(request.displayName() != null
                        ? request.displayName().strip() : request.username())
                .build();
        user.addRole(userRole);

        userRepository.save(user);
        log.info("New user registered: {}", user.getEmail());

        return generateAuthResponse(user);
    }

    // ---- Login ----

    @Transactional
    public AuthResponse login(LoginRequest request) {
        // AuthenticationManager throws BadCredentialsException on failure
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.email().toLowerCase().strip(),
                        request.password()
                )
        );

        User user = userRepository.findByEmail(request.email().toLowerCase().strip())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.email()));

        user.resetFailedLoginAttempts();
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        log.info("User logged in: {}", user.getEmail());
        return generateAuthResponse(user);
    }

    // ---- Token refresh ----

    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        String tokenHash = hashToken(request.refreshToken());

        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException("INVALID_TOKEN", "Invalid or expired refresh token"));

        if (!storedToken.isValid()) {
            throw new BusinessException("INVALID_TOKEN", "Refresh token has expired or been revoked");
        }

        User user = storedToken.getUser();
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());

        // Rotate: revoke old token, issue new access token
        storedToken.revoke();
        refreshTokenRepository.save(storedToken);

        String newAccessToken = jwtService.generateAccessToken(userDetails, user.getId(), user.getUsername());

        return new AuthResponse(
                newAccessToken,
                null,
                appProperties.getSecurity().getJwt().getAccessTokenExpiryMs(),
                toUserSummary(user)
        );
    }

    // ---- Logout ----

    @Transactional
    public void logout(String refreshToken) {
        String tokenHash = hashToken(refreshToken);
        refreshTokenRepository.findByTokenHash(tokenHash)
                .ifPresent(token -> {
                    token.revoke();
                    refreshTokenRepository.save(token);
                });
    }

    // ---- Scheduled cleanup ----

    @Scheduled(cron = "0 0 3 * * *")  // Daily at 3 AM
    @Transactional
    public void cleanupExpiredTokens() {
        int deleted = refreshTokenRepository.deleteExpiredAndRevoked(Instant.now());
        log.info("Cleaned up {} expired/revoked refresh tokens", deleted);
    }

    // ---- Helpers ----

    private AuthResponse generateAuthResponse(User user) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());

        String accessToken = jwtService.generateAccessToken(
                userDetails, user.getId(), user.getUsername());
        String rawRefreshToken = jwtService.generateRefreshToken(user.getEmail());

        // Store only the hash of the refresh token
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(hashToken(rawRefreshToken))
                .expiresAt(Instant.now().plusMillis(
                        appProperties.getSecurity().getJwt().getRefreshTokenExpiryMs()))
                .build();
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(
                accessToken,
                rawRefreshToken,
                appProperties.getSecurity().getJwt().getAccessTokenExpiryMs(),
                toUserSummary(user)
        );
    }

    private AuthResponse.UserSummary toUserSummary(User user) {
        return new AuthResponse.UserSummary(
                user.getId(),
                user.getEmail(),
                user.getUsername(),
                user.getDisplayName(),
                user.getAvatarUrl()
        );
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

