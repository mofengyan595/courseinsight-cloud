package com.courseinsight.server.exception;

public class InvalidCsvFileException extends RuntimeException {

    public InvalidCsvFileException(String message) {
        super(message);
    }

    public InvalidCsvFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
