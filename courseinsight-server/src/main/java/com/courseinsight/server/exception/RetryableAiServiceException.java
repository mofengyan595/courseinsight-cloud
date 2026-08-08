package com.courseinsight.server.exception;

public class RetryableAiServiceException extends AiServiceException {

    public RetryableAiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
