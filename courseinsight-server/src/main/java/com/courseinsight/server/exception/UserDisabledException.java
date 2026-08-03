package com.courseinsight.server.exception;

public class UserDisabledException extends RuntimeException {

    public UserDisabledException(String message) {
        super(message);
    }
}
