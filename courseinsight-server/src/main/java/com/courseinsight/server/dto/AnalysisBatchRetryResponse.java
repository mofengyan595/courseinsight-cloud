package com.courseinsight.server.dto;

import java.time.LocalDateTime;

public record AnalysisBatchRetryResponse(
        Long batchId,
        int requeuedCount,
        LocalDateTime requestedAt) {
}
