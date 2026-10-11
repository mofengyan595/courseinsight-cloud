package com.courseinsight.server.dto;

import com.courseinsight.server.entity.AnalysisResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AnalysisResultResponse(
        Long id,
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
        LocalDateTime createdAt
) {
    public static AnalysisResultResponse from(AnalysisResult result, ObjectMapper objectMapper) {
        return new AnalysisResultResponse(
                result.getId(), result.getLanguage(), result.getSentiment(),
                result.getConfidence(), result.getSentimentSource(), result.getSentimentDevice(),
                readJson(result.getTopicsJson(), objectMapper),
                readJson(result.getTopicEvidenceJson(), objectMapper),
                readJson(result.getKeywordsJson(), objectMapper),
                result.getLongTextHandled(), result.getLongTextTruncated(),
                readJson(result.getAdviceJson(), objectMapper),
                result.getRiskLevel(), result.getAdviceSource(), result.getCreatedAt()
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
