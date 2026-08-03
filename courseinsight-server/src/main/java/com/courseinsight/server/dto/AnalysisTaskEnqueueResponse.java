package com.courseinsight.server.dto;

public record AnalysisTaskEnqueueResponse(
        String eventId,
        Long taskId,
        Long commentId,
        String messageId) {
}
