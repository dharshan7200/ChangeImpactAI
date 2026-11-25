package com.changeimpact.ingestion.workspace;

import com.changeimpact.common.config.AppProperties;
import com.changeimpact.common.exception.IngestionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.*;
import java.util.*;

/**
 * SSRF protection for Git network operations. A clone URL must be {@code https://} to an allowed host (github.com
 * and the configured GitHub Enterprise host by default), must not carry credentials, and its host must not resolve to
 * loopback, private, link-local (incl. cloud metadata 169.254.169.254), CGNAT, multicast or unspecified addresses.
 * {@code file://} repositories are accepted only when explicitly enabled (tests, local demos).
 */
@Component
@Slf4j
public class GitUrlGuard {

    private final AppProperties.Ingestion config;
    private final Set<String> allowedHosts;

    public GitUrlGuard(AppProperties appProperties) {
        this.config = appProperties.getIngestion();
        Set<String> hosts = new HashSet<>();
        config.getAllowedGitHosts().forEach(h -> hosts.add(h.trim().toLowerCase(Locale.ROOT)));
        try {
            String apiHost = URI.create(appProperties.getGithub().getApiBaseUrl()).getHost();
            if (apiHost != null && !apiHost.equalsIgnoreCase("api.github.com")) hosts.add(apiHost.toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
            // invalid API base URL is reported by the GitHub client itself
        }
        this.allowedHosts = Set.copyOf(hosts);
    }

    public void check(String cloneUrl) {
        URI uri;
        try {
            uri = new URI(cloneUrl);
        } catch (URISyntaxException | NullPointerException e) {
            throw new IngestionException("Invalid repository URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("file".equals(scheme)) {
            if (!config.isAllowLocalRepositories()) throw new IngestionException("Local (file://) repositories are not allowed");
            return;
        }
        if (!"https".equals(scheme)) throw new IngestionException("Only https:// repository URLs are allowed");
        if (uri.getRawUserInfo() != null) throw new IngestionException("Repository URLs must not contain credentials");
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!allowedHosts.contains(host)) {
            throw new IngestionException("Git host '" + host + "' is not allowed (app.ingestion.allowed-git-hosts)");
        }
        if (config.isAllowPrivateGitAddresses()) return;
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // not resolvable locally (e.g. egress through an HTTP proxy): the host allowlist still applies
            log.debug("Git host {} not resolvable locally", host);
            return;
        }
        for (InetAddress a : addresses) {
            if (isInternal(a)) {
                throw new IngestionException("Git host '" + host + "' resolves to an internal address; refused");
            }
        }
    }

    static boolean isInternal(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff, second = b[1] & 0xff;
            return first == 0 || (first == 100 && second >= 64 && second <= 127) // CGNAT 100.64/10
                    || (first == 192 && second == 0 && (b[2] & 0xff) == 0);      // 192.0.0.0/24
        }
        return (b[0] & 0xfe) == 0xfc; // IPv6 unique local fc00::/7
    }
}
