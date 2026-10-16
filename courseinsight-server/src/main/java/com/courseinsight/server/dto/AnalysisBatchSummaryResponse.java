package com.courseinsight.server.dto;

import java.time.LocalDateTime;

public record AnalysisBatchSummaryResponse(
        Long batchId,
        String batchNo,
        Long courseId,
        String courseName,
        String originalFilename,
        Integer totalCount,
        LocalDateTime createdAt
) {
}
