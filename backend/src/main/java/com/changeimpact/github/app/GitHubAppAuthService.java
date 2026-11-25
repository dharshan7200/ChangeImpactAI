package com.changeimpact.github.app;

import com.changeimpact.common.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GitHub App authentication.
 *
 * <ol>
 *   <li>An App JWT (RS256) signed with the App's private key: {@code iat} = now − 60 s (clock drift),
 *       {@code exp} = now + 9 min (GitHub allows at most 10), {@code iss} = client ID (recommended) or App ID.</li>
 *   <li>The JWT is exchanged for an installation access token ({@code POST /app/installations/{id}/access_tokens}),
 *       scoped to the one repository and to {@code checks:write} + {@code contents:read}. Tokens are cached
 *       until 5 minutes before they expire (GitHub issues them for one hour).</li>
 * </ol>
 * The private key is read once and never logged.
 */
@Service
@Slf4j
public class GitHubAppAuthService {

    private final AppProperties.GitHub config;
    private final RestClient restClient;
    private final Clock clock;
    private volatile PrivateKey privateKey;
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    private record CachedToken(String token, Instant expiresAt) {}

    @org.springframework.beans.factory.annotation.Autowired
    public GitHubAppAuthService(AppProperties appProperties, GitHubHttp http) {
        this(appProperties, http.client(), Clock.systemUTC());
    }

    GitHubAppAuthService(AppProperties appProperties, RestClient restClient, Clock clock) {
        this.config = appProperties.getGithub();
        this.restClient = restClient;
        this.clock = clock;
    }

    /** True when an App ID (or client ID) and a private key are configured. */
    public boolean isConfigured() {
        return (notBlank(config.getClientId()) || notBlank(config.getAppId()))
                && (notBlank(config.getPrivateKey()) || notBlank(config.getPrivateKeyPath()));
    }

    /** App JWT for {@code /app/...} endpoints. */
    public String createAppJwt() {
        long now = clock.instant().getEpochSecond();
        String issuer = notBlank(config.getClientId()) ? config.getClientId().trim() : config.getAppId().trim();
        String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64(("{\"iat\":" + (now - 60) + ",\"exp\":" + (now + 540) + ",\"iss\":\"" + jsonEscape(issuer) + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(key());
            sig.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return header + "." + payload + "." + b64(sig.sign());
        } catch (GitHubAppException e) {
            throw e;
        } catch (Exception e) {
            throw new GitHubAppException("Could not sign the GitHub App JWT: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Installation token for one repository ({@code owner/name}); cached per installation and repository.
     */
    public String installationToken(long installationId, String repositoryFullName) {
        String repoName = repositoryFullName.substring(repositoryFullName.indexOf('/') + 1);
        String cacheKey = installationId + "|" + repositoryFullName.toLowerCase(Locale.ROOT);
        CachedToken cached = tokens.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(clock.instant().plusSeconds(300))) {
            return cached.token();
        }
        Map<String, Object> body = Map.of(
                "repositories", List.of(repoName),
                "permissions", Map.of("checks", "write", "contents", "read"));
        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/app/installations/{id}/access_tokens", installationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + createAppJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (GitHubAppException e) {
            throw e;
        } catch (Exception e) {
            throw new GitHubAppException("Installation token request failed: " + CheckRunPublisher.describe(e));
        }
        if (response == null || response.path("token").asString("").isEmpty()) {
            throw new GitHubAppException("Installation token response did not contain a token");
        }
        String token = response.path("token").asString();
        Instant expires;
        try {
            expires = Instant.parse(response.path("expires_at").asString());
        } catch (Exception e) {
            expires = clock.instant().plusSeconds(3000);
        }
        tokens.put(cacheKey, new CachedToken(token, expires));
        return token;
    }

    // ------------------------------------------------------------------

    private PrivateKey key() {
        PrivateKey k = privateKey;
        if (k == null) {
            synchronized (this) {
                if (privateKey == null) {
                    privateKey = loadKey();
                }
                k = privateKey;
            }
        }
        return k;
    }

    private PrivateKey loadKey() {
        String pem;
        if (notBlank(config.getPrivateKey())) {
            pem = config.getPrivateKey();
        } else if (notBlank(config.getPrivateKeyPath())) {
            try {
                pem = Files.readString(Path.of(config.getPrivateKeyPath().trim()));
            } catch (Exception e) {
                throw new GitHubAppException("GitHub App private key file cannot be read");
            }
        } else {
            throw new GitHubAppException("GitHub App private key is not configured");
        }
        return parsePrivateKey(pem);
    }

    /** Parses a PEM RSA private key in PKCS#8 ({@code BEGIN PRIVATE KEY}) or PKCS#1 ({@code BEGIN RSA PRIVATE KEY}) form. */
    static PrivateKey parsePrivateKey(String pem) {
        // Environment variables often carry the PEM with literal "\n"
        String text = pem.replace("\\n", "\n").trim();
        boolean pkcs1 = text.contains("BEGIN RSA PRIVATE KEY");
        String base64 = text.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            if (pkcs1) der = pkcs1ToPkcs8(der);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new GitHubAppException("GitHub App private key is not a valid RSA PEM key");
        }
    }

    /**
     * Wraps a PKCS#1 RSAPrivateKey in a PKCS#8 PrivateKeyInfo:
     * {@code SEQUENCE { INTEGER 0, SEQUENCE { OID 1.2.840.113549.1.1.1, NULL }, OCTET STRING { pkcs1 } }}.
     */
    static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
        byte[] octet = der(0x04, pkcs1);
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.writeBytes(version);
        inner.writeBytes(algorithm);
        inner.writeBytes(octet);
        return der(0x30, inner.toByteArray());
    }

    private static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = content.length;
        if (len < 0x80) {
            out.write(len);
        } else {
            int bytes = len > 0xFFFFFF ? 4 : len > 0xFFFF ? 3 : len > 0xFF ? 2 : 1;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) out.write((len >>> (8 * i)) & 0xFF);
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String jsonEscape(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    /** Configuration or GitHub authentication problem; the message never contains secrets. */
    public static class GitHubAppException extends RuntimeException {
        public GitHubAppException(String message) {
            super(message);
        }
    }
}
