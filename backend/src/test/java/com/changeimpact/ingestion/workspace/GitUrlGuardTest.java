package com.changeimpact.ingestion.workspace;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.common.exception.IngestionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SSRF guard for Git URLs")
class GitUrlGuardTest {

    private GitUrlGuard guard(boolean local, boolean privateAddresses, String apiBase) {
        AppProperties p = new AppProperties();
        p.getIngestion().setAllowLocalRepositories(local);
        p.getIngestion().setAllowPrivateGitAddresses(privateAddresses);
        if (apiBase != null) p.getGithub().setApiBaseUrl(apiBase);
        return new GitUrlGuard(p);
    }

    @Test
    @DisplayName("https to github.com passes; other schemes, hosts and embedded credentials are refused")
    void schemesAndHosts() {
        GitUrlGuard g = guard(false, true, null);
        assertDoesNotThrow(() -> g.check("https://github.com/spring-projects/spring-petclinic.git"));
        assertThrows(IngestionException.class, () -> g.check("http://github.com/a/b.git"));
        assertThrows(IngestionException.class, () -> g.check("ssh://git@github.com/a/b.git"));
        assertThrows(IngestionException.class, () -> g.check("https://evil.example.com/a/b.git"));
        assertThrows(IngestionException.class, () -> g.check("https://user:pass@github.com/a/b.git"));
        assertThrows(IngestionException.class, () -> g.check("file:///etc"));
        assertThrows(IngestionException.class, () -> g.check("not a url"));
    }

    @Test
    @DisplayName("file:// only with the explicit flag; the GitHub Enterprise API host is allowed automatically")
    void localAndEnterprise() {
        assertDoesNotThrow(() -> guard(true, false, null).check("file:///tmp/repo.git"));
        assertDoesNotThrow(() -> guard(false, true, "https://ghe.corp.example/api/v3").check("https://ghe.corp.example/team/app.git"));
    }

    @Test
    @DisplayName("allowed hosts resolving to internal addresses are refused (DNS rebinding, metadata endpoints)")
    void internalAddresses() throws Exception {
        AppProperties p = new AppProperties();
        p.getIngestion().setAllowedGitHosts(java.util.List.of("localhost"));
        assertThrows(IngestionException.class, () -> new GitUrlGuard(p).check("https://localhost/a/b.git"));
        for (String ip : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1"}) {
            assertTrue(GitUrlGuard.isInternal(InetAddress.getByName(ip)), ip);
        }
        for (String ip : new String[]{"140.82.114.4", "8.8.8.8", "2606:4700::1111"}) {
            assertFalse(GitUrlGuard.isInternal(InetAddress.getByName(ip)), ip);
        }
    }
}
