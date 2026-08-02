package com.courseinsight.server.client;

public record AiAnalysisRequest(
        Long taskId,
        Long commentId,
        String text,
        boolean includeAdvice
) {
}
