package com.courseinsight.server.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class AnalysisBatchResultRow {

    private Long taskId;
    private Long commentId;
    private String content;
    private Integer rating;
    private String taskStatus;
    private Integer retryCount;
    private String failureReason;
    private LocalDateTime deadLetteredAt;
    private LocalDateTime taskCompletedAt;
    private Long resultId;
    private String language;
    private String sentiment;
    private BigDecimal confidence;
    private String sentimentSource;
    private String sentimentDevice;
    private String topicsJson;
    private String topicEvidenceJson;
    private String keywordsJson;
    private Boolean longTextHandled;
    private Boolean longTextTruncated;
    private String adviceJson;
    private String riskLevel;
    private String adviceSource;
    private LocalDateTime resultCreatedAt;
}
