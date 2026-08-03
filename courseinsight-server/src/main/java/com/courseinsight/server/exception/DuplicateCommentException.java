package com.courseinsight.server.exception;

public class DuplicateCommentException extends RuntimeException {

    public DuplicateCommentException(String message) {
        super(message);
    }
}
