package com.courseinsight.server.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AnalysisBatchResultItemResponse(
        Long taskId,
        Long commentId,
        String content,
        Integer rating,
        String taskStatus,
        Integer retryCount,
        String failureReason,
        LocalDateTime deadLetteredAt,
        LocalDateTime taskCompletedAt,
        Long resultId,
        String language,
        String sentiment,
        BigDecimal confidence,
        String sentimentSource,
        String sentimentDevice,
        JsonNode topics,
        JsonNode topicEvidence,
        JsonNode keywords,
        Boolean longTextHandled,
        Boolean longTextTruncated,
        JsonNode advice,
        String riskLevel,
        String adviceSource,
        LocalDateTime resultCreatedAt) {

    public static AnalysisBatchResultItemResponse from(
            AnalysisBatchResultRow row,
            ObjectMapper objectMapper) {
        return new AnalysisBatchResultItemResponse(
                row.getTaskId(),
                row.getCommentId(),
                row.getContent(),
                row.getRating(),
                row.getTaskStatus(),
                row.getRetryCount(),
                row.getFailureReason(),
                row.getDeadLetteredAt(),
                row.getTaskCompletedAt(),
                row.getResultId(),
                row.getLanguage(),
                row.getSentiment(),
                row.getConfidence(),
                row.getSentimentSource(),
                row.getSentimentDevice(),
                readJson(row.getTopicsJson(), objectMapper),
                readJson(row.getTopicEvidenceJson(), objectMapper),
                readJson(row.getKeywordsJson(), objectMapper),
                row.getLongTextHandled(),
                row.getLongTextTruncated(),
                readJson(row.getAdviceJson(), objectMapper),
                row.getRiskLevel(),
                row.getAdviceSource(),
                row.getResultCreatedAt()
        );
    }

    private static JsonNode readJson(String value, ObjectMapper objectMapper) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的分析结果 JSON 无法解析", exception);
        }
    }
}
