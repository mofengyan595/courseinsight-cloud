package com.courseinsight.server.client;

import java.math.BigDecimal;
import java.util.List;

public record AiAnalysisResponse(
        Long taskId,
        Long commentId,
        String language,
        String sentiment,
        BigDecimal confidence,
        String sentimentSource,
        String sentimentDevice,
        List<String> topics,
        List<TopicEvidence> topicEvidence,
        List<String> keywords,
        boolean longTextHandled,
        boolean longTextTruncated,
        ReviewAdvice advice
) {

    public record TopicEvidence(
            String aspect,
            List<String> keywords,
            String evidence
    ) {
    }

    public record AdviceProblem(
            String aspect,
            String description,
            String evidence
    ) {
    }

    public record AdviceSuggestion(
            String aspect,
            String suggestion,
            String evidence,
            String actionType
    ) {
    }

    public record ReviewAdvice(
            String summary,
            List<AdviceProblem> problems,
            List<AdviceSuggestion> suggestions,
            String riskLevel,
            String source,
            String language,
            String fallbackReason
    ) {
    }
}
