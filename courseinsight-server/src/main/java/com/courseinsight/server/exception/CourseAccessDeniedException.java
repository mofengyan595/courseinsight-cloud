package com.courseinsight.server.exception;

public class CourseAccessDeniedException extends RuntimeException {

    public CourseAccessDeniedException(String message) {
        super(message);
    }
}
