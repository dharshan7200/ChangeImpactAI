package com.changeimpact.github.app;

import com.changeimpact.common.config.AppProperties;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared HTTP client for GitHub App calls (installation tokens, Checks API) with explicit timeouts:
 * webhook handling waits on these calls and GitHub expects a webhook response within 10 seconds.
 */
@Component
public class GitHubHttp {

    private final RestClient client;

    public GitHubHttp(AppProperties appProperties, RestClient.Builder builder) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = builder.clone()
                .baseUrl(appProperties.getGithub().getApiBaseUrl())
                // buffered: small JSON bodies are sent with Content-Length instead of chunked encoding
                .requestFactory(new BufferingClientHttpRequestFactory(factory))
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("User-Agent", "ChangeImpact-AI")
                .build();
    }

    public RestClient client() {
        return client;
    }
}
