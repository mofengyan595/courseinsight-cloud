package com.courseinsight.server.dto;

public record AnalysisBatchCreateResponse(
        Long batchId,
        String batchNo,
        Long courseId,
        int totalCount) {
}
