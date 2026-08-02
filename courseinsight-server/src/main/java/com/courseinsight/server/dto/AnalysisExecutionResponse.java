package com.courseinsight.server.dto;

import com.courseinsight.server.entity.AnalysisResult;

import java.math.BigDecimal;

public record AnalysisExecutionResponse(
        Long resultId,
        Long taskId,
        Long commentId,
        String status,
        String language,
        String sentiment,
        BigDecimal confidence,
        String riskLevel,
        String adviceSource
) {

    public static AnalysisExecutionResponse success(AnalysisResult result) {
        return new AnalysisExecutionResponse(
                result.getId(),
                result.getTaskId(),
                result.getCommentId(),
                "SUCCESS",
                result.getLanguage(),
                result.getSentiment(),
                result.getConfidence(),
                result.getRiskLevel(),
                result.getAdviceSource()
        );
    }
}
