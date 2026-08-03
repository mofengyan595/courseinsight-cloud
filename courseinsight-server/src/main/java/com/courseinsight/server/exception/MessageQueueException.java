package com.courseinsight.server.exception;

public class MessageQueueException extends RuntimeException {

    public MessageQueueException(String message) {
        super(message);
    }

    public MessageQueueException(String message, Throwable cause) {
        super(message, cause);
    }
}
