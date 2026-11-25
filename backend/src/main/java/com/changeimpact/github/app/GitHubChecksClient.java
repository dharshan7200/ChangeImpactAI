package com.changeimpact.github.app;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/** Minimal GitHub Checks API client (create and update check runs) authenticated with an installation token. */
@Component
public class GitHubChecksClient {

    private final RestClient client;

    public GitHubChecksClient(GitHubHttp http) {
        this.client = http.client();
    }

    /** {@code POST /repos/{owner}/{repo}/check-runs}; returns the check run id. */
    public long create(String token, String repositoryFullName, Map<String, Object> body) {
        String[] parts = split(repositoryFullName);
        JsonNode response = client.post()
                .uri("/repos/{owner}/{repo}/check-runs", parts[0], parts[1])
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        long id = response == null ? 0 : response.path("id").asLong(0);
        if (id == 0) {
            throw new GitHubAppAuthService.GitHubAppException("Check run creation returned no id");
        }
        return id;
    }

    /** {@code PATCH /repos/{owner}/{repo}/check-runs/{id}}. */
    public void update(String token, String repositoryFullName, long checkRunId, Map<String, Object> body) {
        String[] parts = split(repositoryFullName);
        client.patch()
                .uri("/repos/{owner}/{repo}/check-runs/{id}", parts[0], parts[1], checkRunId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private static String[] split(String fullName) {
        int slash = fullName == null ? -1 : fullName.indexOf('/');
        if (slash <= 0 || slash == fullName.length() - 1) {
            throw new IllegalArgumentException("Repository full name must be owner/name");
        }
        return new String[]{fullName.substring(0, slash), fullName.substring(slash + 1)};
    }
}
