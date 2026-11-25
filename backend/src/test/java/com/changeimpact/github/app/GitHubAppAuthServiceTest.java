package com.changeimpact.github.app;

import com.changeimpact.common.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("GitHub App authentication: RS256 JWT, PKCS#1 keys, installation tokens")
class GitHubAppAuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static KeyPair keys;

    private final AppProperties props = new AppProperties();
    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private GitHubAppAuthService service;

    @BeforeEach
    void setUp() throws Exception {
        if (keys == null) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            keys = g.generateKeyPair();
        }
        props.getGithub().setAppId("424242");
        props.getGithub().setPrivateKey(pem("PRIVATE KEY", keys.getPrivate().getEncoded()));
        builder = RestClient.builder().baseUrl("https://api.github.test");
        server = MockRestServiceServer.bindTo(builder).build();
        service = new GitHubAppAuthService(props, builder.build(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der) + "\n-----END " + type + "-----\n";
    }

    private static JsonNode part(String jwt, int i) {
        return JSON.readTree(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[i]), StandardCharsets.UTF_8));
    }

    private static boolean signatureValid(String jwt) throws Exception {
        String[] p = jwt.split("\\.");
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initVerify(keys.getPublic());
        s.update((p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII));
        return s.verify(Base64.getUrlDecoder().decode(p[2]));
    }

    @Test
    @DisplayName("JWT: RS256 signature verifies; iat backdated 60 s, exp 9 min ahead; issuer = client ID, else App ID")
    void jwt() throws Exception {
        String jwt = service.createAppJwt();
        assertTrue(signatureValid(jwt));
        assertEquals("RS256", part(jwt, 0).path("alg").asString());
        JsonNode claims = part(jwt, 1);
        assertEquals(NOW.getEpochSecond() - 60, claims.path("iat").asLong());
        assertEquals(NOW.getEpochSecond() + 540, claims.path("exp").asLong());
        assertTrue(claims.path("exp").asLong() - claims.path("iat").asLong() <= 600, "GitHub allows at most 10 minutes");
        assertEquals("424242", claims.path("iss").asString());

        props.getGithub().setClientId("Iv23liTEST");
        assertEquals("Iv23liTEST", part(service.createAppJwt(), 1).path("iss").asString());
    }

    @Test
    @DisplayName("PKCS#1 keys (GitHub's download format, also with literal \\n from env vars) are converted exactly to PKCS#8")
    void pkcs1() throws Exception {
        byte[] pkcs8 = keys.getPrivate().getEncoded();
        byte[] pkcs1 = Arrays.copyOfRange(pkcs8, 26, pkcs8.length); // PrivateKeyInfo header of a 2048-bit RSA key
        assertArrayEquals(pkcs8, GitHubAppAuthService.pkcs1ToPkcs8(pkcs1));

        String envStyle = pem("RSA PRIVATE KEY", pkcs1).replace("\n", "\\n");
        assertEquals(keys.getPrivate(), GitHubAppAuthService.parsePrivateKey(envStyle));
        props.getGithub().setPrivateKey(envStyle);
        service = new GitHubAppAuthService(props, builder.build(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertTrue(signatureValid(service.createAppJwt()));
        assertThrows(GitHubAppAuthService.GitHubAppException.class, () -> GitHubAppAuthService.parsePrivateKey("not a key"));
    }

    @Test
    @DisplayName("installation token: scoped to one repository with checks:write + contents:read, cached until near expiry")
    void installationToken() {
        server.expect(requestTo("https://api.github.test/app/installations/55501/access_tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Bearer ey")))
                .andExpect(jsonPath("$.repositories[0]").value("demo-service"))
                .andExpect(jsonPath("$.permissions.checks").value("write"))
                .andExpect(jsonPath("$.permissions.contents").value("read"))
                .andRespond(withSuccess("{\"token\":\"ghs_abc123\",\"expires_at\":\"2026-11-18T11:00:00Z\"}", MediaType.APPLICATION_JSON));

        assertEquals("ghs_abc123", service.installationToken(55501, "demo-org/demo-service"));
        assertEquals("ghs_abc123", service.installationToken(55501, "demo-org/demo-service"), "second call served from cache");
        server.verify(); // exactly one request
    }

    @Test
    @DisplayName("configured only with an id and a key")
    void configured() {
        assertTrue(service.isConfigured());
        props.getGithub().setPrivateKey(null);
        assertFalse(service.isConfigured());
    }
}
