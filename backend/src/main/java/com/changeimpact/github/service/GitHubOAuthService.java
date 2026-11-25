package com.changeimpact.github.service;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.common.exception.BusinessException;
import com.changeimpact.repository.entity.ConnectedAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GitHub OAuth web flow for connecting a GitHub account (and with it, private repositories).
 *
 * <ol>
 *   <li>{@link #authorizationUrl}: GitHub's consent page URL with a {@code state} that is HMAC-signed, bound to the
 *       platform user, valid for 10 minutes and accepted once (CSRF protection).</li>
 *   <li>GitHub redirects the browser to the frontend's callback page, which posts {@code code} + {@code state} to
 *       {@link #complete}: the state is verified, the code exchanged for a token server-side (the client secret never
 *       reaches the browser) and the account connected through {@link GitHubAccountService} (token AES-GCM encrypted).</li>
 * </ol>
 */
@Service
@Slf4j
public class GitHubOAuthService {

    static final Duration STATE_TTL = Duration.ofMinutes(10);

    public record OAuthToken(String accessToken, String scope) {}

    private final AppProperties.GitHub.OAuth config;
    private final GitHubAccountService accounts;
    private final RestClient http;
    private final byte[] stateKey;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> usedStates = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public GitHubOAuthService(AppProperties properties, GitHubAccountService accounts) {
        this(properties, accounts, Clock.systemUTC());
    }

    GitHubOAuthService(AppProperties properties, GitHubAccountService accounts, Clock clock) {
        this.config = properties.getGithub().getOauth();
        this.accounts = accounts;
        this.clock = clock;
        // a key for signing states only, derived from the JWT secret (never used directly)
        this.stateKey = hmac(properties.getSecurity().getJwt().getSecret().getBytes(StandardCharsets.UTF_8),
                "changeimpact-github-oauth-state".getBytes(StandardCharsets.UTF_8));
        this.http = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10)).build()))
                .build();
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", config.isConfigured());
        m.put("scopes", config.getScopes());
        m.put("callbackUrl", config.getRedirectUri());
        return m;
    }

    public String authorizationUrl(UUID userId) {
        requireConfigured();
        return UriComponentsBuilder.fromUriString(trim(config.getWebBaseUrl()) + "/login/oauth/authorize")
                .queryParam("client_id", config.getClientId())
                .queryParam("redirect_uri", config.getRedirectUri())
                .queryParam("scope", config.getScopes())
                .queryParam("state", newState(userId))
                .queryParam("allow_signup", "false")
                .encode().build().toUriString();
    }

    public ConnectedAccount complete(UUID userId, String code, String state) {
        requireConfigured();
        if (code == null || code.isBlank() || code.length() > 200) {
            throw new BusinessException("OAUTH_INVALID_CODE", "Missing or invalid authorization code");
        }
        verifyState(userId, state);
        OAuthToken token = exchange(code);
        ConnectedAccount account = accounts.connectGitHubAccount(userId, token.accessToken(),
                token.scope() == null || token.scope().isBlank() ? null : token.scope().split("[,\\s]+"));
        log.info("GitHub account {} connected via OAuth (scopes: {})", account.getLogin(), token.scope());
        return account;
    }

    OAuthToken exchange(String code) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", config.getClientId());
        form.add("client_secret", config.getClientSecret());
        form.add("code", code);
        form.add("redirect_uri", config.getRedirectUri());
        Map<?, ?> body;
        try {
            body = http.post().uri(trim(config.getWebBaseUrl()) + "/login/oauth/access_token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON)
                    .body(form).retrieve().body(Map.class);
        } catch (RestClientException e) {
            throw new BusinessException("OAUTH_EXCHANGE_FAILED", "GitHub did not accept the authorization: " + e.getMessage());
        }
        if (body == null || body.get("access_token") == null) {
            // GitHub answers 200 with {"error": "bad_verification_code", "error_description": ...}
            Object err = body == null ? "empty response" : body.get("error_description") != null ? body.get("error_description") : body.get("error");
            throw new BusinessException("OAUTH_EXCHANGE_FAILED", "GitHub did not accept the authorization: " + err);
        }
        return new OAuthToken(String.valueOf(body.get("access_token")), body.get("scope") == null ? null : String.valueOf(body.get("scope")));
    }

    // ---- state: base64url(userId|expiresAtMillis|nonce) + "." + base64url(hmac) ---------------------------------

    String newState(UUID userId) {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String payload = userId + "|" + (clock.millis() + STATE_TTL.toMillis()) + "|" + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        String p = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return p + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(stateKey, p.getBytes(StandardCharsets.UTF_8)));
    }

    void verifyState(UUID userId, String state) {
        if (state == null || state.length() > 512 || !state.contains(".")) throw invalidState();
        String p = state.substring(0, state.indexOf('.')), sig = state.substring(state.indexOf('.') + 1);
        byte[] expected = hmac(stateKey, p.getBytes(StandardCharsets.UTF_8));
        byte[] given;
        try {
            given = Base64.getUrlDecoder().decode(sig);
        } catch (IllegalArgumentException e) {
            throw invalidState();
        }
        if (!MessageDigest.isEqual(expected, given)) throw invalidState();
        String[] parts = new String(Base64.getUrlDecoder().decode(p), StandardCharsets.UTF_8).split("\\|");
        if (parts.length != 3 || !parts[0].equals(userId.toString())) throw invalidState();
        long expires = Long.parseLong(parts[1]);
        long now = clock.millis();
        if (now > expires) throw new BusinessException("OAUTH_STATE_EXPIRED", "The GitHub authorization took too long; please start again");
        usedStates.entrySet().removeIf(e -> e.getValue() < now);
        if (usedStates.putIfAbsent(parts[2], expires) != null) throw invalidState(); // replayed
    }

    private static BusinessException invalidState() {
        return new BusinessException("OAUTH_INVALID_STATE", "Invalid or reused authorization state; please start again");
    }

    private void requireConfigured() {
        if (!config.isConfigured()) {
            throw new BusinessException("OAUTH_NOT_CONFIGURED",
                    "GitHub OAuth is not configured (GITHUB_OAUTH_CLIENT_ID / GITHUB_OAUTH_CLIENT_SECRET)");
        }
    }

    private static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
