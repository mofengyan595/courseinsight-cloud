package com.courseinsight.server.dto;

import com.courseinsight.server.entity.AnalysisTask;

import java.time.LocalDateTime;

public record AnalysisTaskDetailResponse(
        Long id,
        String taskNo,
        Long commentId,
        Long courseId,
        String status,
        Integer retryCount,
        String failureReason,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime deadLetteredAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static AnalysisTaskDetailResponse from(AnalysisTask task) {
        return new AnalysisTaskDetailResponse(
                task.getId(),
                task.getTaskNo(),
                task.getCommentId(),
                task.getCourseId(),
                task.getStatus(),
                task.getRetryCount(),
                task.getFailureReason(),
                task.getStartedAt(),
                task.getCompletedAt(),
                task.getDeadLetteredAt(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
