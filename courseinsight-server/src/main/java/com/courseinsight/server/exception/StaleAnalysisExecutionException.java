package com.courseinsight.server.exception;

public class StaleAnalysisExecutionException extends AnalysisTaskConflictException {

    public StaleAnalysisExecutionException(String message) {
        super(message);
    }
}
