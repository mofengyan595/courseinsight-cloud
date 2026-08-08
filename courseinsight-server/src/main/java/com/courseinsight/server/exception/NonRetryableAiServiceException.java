package com.courseinsight.server.exception;

public class NonRetryableAiServiceException extends AiServiceException {

    public NonRetryableAiServiceException(String message) {
        super(message);
    }

    public NonRetryableAiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
