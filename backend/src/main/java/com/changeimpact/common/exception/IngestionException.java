package com.changeimpact.common.exception;

/**
 * Thrown when repository ingestion encounters an unrecoverable error.
 */
public class IngestionException extends RuntimeException {

    public IngestionException(String message) {
        super(message);
    }

    public IngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}

