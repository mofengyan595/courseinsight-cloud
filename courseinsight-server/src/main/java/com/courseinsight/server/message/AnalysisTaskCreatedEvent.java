package com.courseinsight.server.message;

public record AnalysisTaskCreatedEvent(
        String eventId,
        Long taskId,
        Long commentId,
        String eventType,
        String createdAt) {

    public static final String EVENT_TYPE = "COMMENT_ANALYSIS_CREATED";
}
