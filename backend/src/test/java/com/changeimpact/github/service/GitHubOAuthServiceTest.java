package com.changeimpact.github.service;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubOAuthServiceTest {

    private final AtomicLong now = new AtomicLong(Instant.parse("2026-11-22T10:00:00Z").toEpochMilli());
    private final Clock clock = new Clock() {
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };

    private GitHubOAuthService service(boolean configured) {
        AppProperties p = new AppProperties();
        p.getSecurity().getJwt().setSecret("test-secret-key-for-testing-only-must-be-32-chars-long");
        if (configured) {
            p.getGithub().getOauth().setClientId("Iv1.client");
            p.getGithub().getOauth().setClientSecret("secret");
        }
        p.getGithub().getOauth().setRedirectUri("https://ci.example.com/github/oauth/callback");
        return new GitHubOAuthService(p, null, clock);
    }

    @Test
    void authorizationUrlCarriesClientScopesRedirectAndState() {
        String url = service(true).authorizationUrl(UUID.randomUUID());
        assertThat(url).startsWith("https://github.com/login/oauth/authorize?")
                .contains("client_id=Iv1.client")
                .contains("redirect_uri=https://ci.example.com/github/oauth/callback")
                .contains("scope=repo%20read:user%20user:email")
                .contains("state=").contains("allow_signup=false");
    }

    @Test
    void stateIsBoundToTheUserSignedSingleUseAndExpires() {
        GitHubOAuthService s = service(true);
        UUID user = UUID.randomUUID(), other = UUID.randomUUID();

        String state = s.newState(user);
        s.verifyState(user, state);                                       // valid once
        assertThatThrownBy(() -> s.verifyState(user, state)).hasMessageContaining("reused"); // replay

        String forOther = s.newState(user);
        assertThatThrownBy(() -> s.verifyState(other, forOther)).isInstanceOf(BusinessException.class); // CSRF: other user

        String tampered = s.newState(user);
        String flipped = tampered.substring(0, tampered.length() - 2) + (tampered.endsWith("A") ? "BB" : "AA");
        assertThatThrownBy(() -> s.verifyState(user, flipped)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> s.verifyState(user, "garbage")).isInstanceOf(BusinessException.class);

        String old = s.newState(user);
        now.addAndGet(GitHubOAuthService.STATE_TTL.toMillis() + 1);
        assertThatThrownBy(() -> s.verifyState(user, old)).hasMessageContaining("too long");
    }

    @Test
    void notConfiguredIsAReadableError() {
        GitHubOAuthService s = service(false);
        assertThat(s.status().get("configured")).isEqualTo(false);
        assertThatThrownBy(() -> s.authorizationUrl(UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo("OAUTH_NOT_CONFIGURED"));
    }
}
