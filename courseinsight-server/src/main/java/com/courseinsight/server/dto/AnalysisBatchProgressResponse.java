package com.courseinsight.server.dto;

import com.courseinsight.server.entity.AnalysisBatchStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

public record AnalysisBatchProgressResponse(
        Long batchId,
        String batchNo,
        Long courseId,
        String originalFilename,
        String status,
        int totalCount,
        int waitingCount,
        int processingCount,
        int retryingCount,
        int successCount,
        int failedCount,
        BigDecimal completionPercentage,
        LocalDateTime createdAt,
        LocalDateTime completedAt) {

    public static AnalysisBatchProgressResponse from(
            AnalysisBatchProgressAggregate aggregate) {
        int total = aggregate.getTotalCount();
        int waiting = toInt(aggregate.getWaitingCount());
        int processing = toInt(aggregate.getProcessingCount());
        int retrying = toInt(aggregate.getRetryingCount());
        int success = toInt(aggregate.getSuccessCount());
        int failed = toInt(aggregate.getFailedCount());
        int completed = success + failed;

        AnalysisBatchStatus status = resolveStatus(total, success, failed);
        BigDecimal percentage = BigDecimal.valueOf(completed)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        LocalDateTime completedAt = status == AnalysisBatchStatus.PROCESSING
                ? null
                : aggregate.getLastCompletedAt();

        return new AnalysisBatchProgressResponse(
                aggregate.getBatchId(),
                aggregate.getBatchNo(),
                aggregate.getCourseId(),
                aggregate.getOriginalFilename(),
                status.name(),
                total,
                waiting,
                processing,
                retrying,
                success,
                failed,
                percentage,
                aggregate.getCreatedAt(),
                completedAt
        );
    }

    private static AnalysisBatchStatus resolveStatus(
            int total,
            int success,
            int failed) {
        if (success + failed < total) {
            return AnalysisBatchStatus.PROCESSING;
        }
        if (failed == 0) {
            return AnalysisBatchStatus.COMPLETED;
        }
        if (success == 0) {
            return AnalysisBatchStatus.FAILED;
        }
        return AnalysisBatchStatus.PARTIAL_FAILED;
    }

    private static int toInt(Long value) {
        return value == null ? 0 : Math.toIntExact(value);
    }
}
