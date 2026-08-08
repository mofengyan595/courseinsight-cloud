package com.courseinsight.server.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class AnalysisBatchProgressAggregate {

    private Long batchId;
    private String batchNo;
    private Long courseId;
    private Long createdBy;
    private String originalFilename;
    private Integer totalCount;
    private Long waitingCount;
    private Long processingCount;
    private Long retryingCount;
    private Long successCount;
    private Long failedCount;
    private LocalDateTime createdAt;
    private LocalDateTime lastCompletedAt;
}
