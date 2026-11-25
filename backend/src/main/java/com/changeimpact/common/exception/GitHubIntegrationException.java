package com.changeimpact.common.exception;

/**
 * Thrown when GitHub API calls fail or the integration encounters an error.
 */
public class GitHubIntegrationException extends RuntimeException {

    private final int statusCode;

    public GitHubIntegrationException(String message) {
        super(message);
        this.statusCode = -1;
    }

    public GitHubIntegrationException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public GitHubIntegrationException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public int getStatusCode() {
        return statusCode;
    }
}

