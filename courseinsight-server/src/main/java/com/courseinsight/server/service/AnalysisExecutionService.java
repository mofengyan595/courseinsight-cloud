package com.courseinsight.server.service;

import com.courseinsight.server.cache.CourseAnalyticsCache;
import com.courseinsight.server.client.AiAnalysisClient;
import com.courseinsight.server.client.AiAnalysisResponse;
import com.courseinsight.server.config.AnalysisExecutionProperties;
import com.courseinsight.server.dto.AnalysisExecutionResponse;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.AnalysisTaskStatus;
import com.courseinsight.server.entity.CourseComment;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.AnalysisTaskConflictException;
import com.courseinsight.server.exception.NonRetryableAiServiceException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.exception.StaleAnalysisExecutionException;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.mapper.CourseCommentMapper;
import com.courseinsight.server.metrics.CourseInsightMetrics;
import com.courseinsight.server.ratelimit.RateLimitPolicy;
import com.courseinsight.server.ratelimit.RedisRateLimiter;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
public class AnalysisExecutionService {

    private final AnalysisTaskMapper analysisTaskMapper;
    private final CourseCommentMapper courseCommentMapper;
    private final AiAnalysisClient aiAnalysisClient;
    private final AnalysisResultPersistenceService persistenceService;
    private final CourseManagementAccessService managementAccessService;
    private final CourseAnalyticsCache courseAnalyticsCache;
    private final RedisRateLimiter rateLimiter;
    private final AnalysisExecutionProperties executionProperties;
    private final CourseInsightMetrics metrics;

    public AnalysisExecutionService(
            AnalysisTaskMapper analysisTaskMapper,
            CourseCommentMapper courseCommentMapper,
            AiAnalysisClient aiAnalysisClient,
            AnalysisResultPersistenceService persistenceService,
            CourseManagementAccessService managementAccessService,
            CourseAnalyticsCache courseAnalyticsCache,
            RedisRateLimiter rateLimiter,
            AnalysisExecutionProperties executionProperties,
            CourseInsightMetrics metrics) {
        this.analysisTaskMapper = analysisTaskMapper;
        this.courseCommentMapper = courseCommentMapper;
        this.aiAnalysisClient = aiAnalysisClient;
        this.persistenceService = persistenceService;
        this.managementAccessService = managementAccessService;
        this.courseAnalyticsCache = courseAnalyticsCache;
        this.rateLimiter = rateLimiter;
        this.executionProperties = executionProperties;
        this.metrics = metrics;
    }

    public AnalysisExecutionResponse execute(Long taskId) {
        AnalysisTask task = requireTask(taskId);
        return executeTask(task, task.getCurrentEventId(), false);
    }

    public void executeFromMessage(Long taskId, String eventId) {
        AnalysisTask task = requireTask(taskId);
        if (isObsoleteMessage(task, eventId)) {
            return;
        }
        executeTask(task, eventId, true);
    }

    public AnalysisExecutionResponse executeForUser(
            Long taskId,
            Long currentUserId,
            UserRole currentRole) {
        rateLimiter.check(RateLimitPolicy.MANUAL_ANALYSIS, currentUserId);
        AnalysisTask task = requireTask(taskId);
        managementAccessService.assertCanManage(
                task.getCourseId(),
                currentUserId,
                currentRole
        );
        return executeTask(task, task.getCurrentEventId(), false);
    }

    private AnalysisExecutionResponse executeTask(
            AnalysisTask task,
            String eventId,
            boolean messageDriven) {
        Long taskId = task.getId();
        if (AnalysisTaskStatus.SUCCESS.name().equals(task.getStatus())) {
            return messageDriven ? null : persistenceService.getSuccessResult(taskId);
        }

        CourseComment comment = courseCommentMapper.selectById(task.getCommentId());
        if (comment == null) {
            throw new ResourceNotFoundException(
                    "分析任务对应的课程评价不存在"
            );
        }

        String executionToken = randomId();
        long leaseDurationMicros = executionProperties.executionLeaseMicros();
        boolean claimed = messageDriven
                ? analysisTaskMapper.claimForEvent(
                        taskId,
                        eventId,
                        executionToken,
                        leaseDurationMicros
                ) == 1
                : analysisTaskMapper.claimManually(
                        taskId,
                        eventId,
                        executionToken,
                        leaseDurationMicros
                ) == 1;
        if (!claimed) {
            if (messageDriven && isObsoleteMessage(requireTask(taskId), eventId)) {
                return null;
            }
            throw new AnalysisTaskConflictException(
                    "分析任务已被其他有效执行占用"
            );
        }

        courseAnalyticsCache.evict(task.getCourseId());
        try {
            AiAnalysisResponse response = aiAnalysisClient.analyze(
                    task.getId(),
                    task.getCommentId(),
                    comment.getContent(),
                    true
            );
            try {
                AnalysisExecutionResponse result = persistenceService.saveSuccess(
                        task,
                        response,
                        eventId,
                        executionToken
                );
                metrics.analysisTaskSucceeded();
                return result;
            } catch (StaleAnalysisExecutionException exception) {
                if (messageDriven) {
                    return null;
                }
                throw exception;
            }
        } catch (StaleAnalysisExecutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            boolean failedByOwner = markFailed(
                    taskId,
                    task.getCourseId(),
                    eventId,
                    executionToken,
                    exception,
                    !messageDriven
            );
            if (!failedByOwner && messageDriven) {
                return null;
            }
            throw exception;
        }
    }

    private AnalysisTask requireTask(Long taskId) {
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            throw new ResourceNotFoundException("分析任务不存在");
        }
        return task;
    }

    private boolean isObsoleteMessage(AnalysisTask task, String eventId) {
        return eventId == null
                || !Objects.equals(eventId, task.getCurrentEventId())
                || AnalysisTaskStatus.SUCCESS.name().equals(task.getStatus())
                || task.getDeadLetteredAt() != null;
    }

    private boolean markFailed(
            Long taskId,
            Long courseId,
            String eventId,
            String executionToken,
            RuntimeException originalException,
            boolean synchronousExecution) {
        String reason = failureReason(originalException);
        boolean terminalFailure = synchronousExecution
                || originalException instanceof NonRetryableAiServiceException;

        try {
            boolean updated = analysisTaskMapper.failOwnedExecution(
                    taskId,
                    eventId,
                    executionToken,
                    reason,
                    terminalFailure
            ) == 1;
            if (updated) {
                metrics.analysisTaskFailed(terminalFailure);
                courseAnalyticsCache.evict(courseId);
            }
            return updated;
        } catch (RuntimeException failureUpdateException) {
            originalException.addSuppressed(failureUpdateException);
            return false;
        }
    }

    private String failureReason(RuntimeException exception) {
        String reason = exception.getMessage();
        if (reason == null || reason.isBlank()) {
            reason = exception.getClass().getSimpleName();
        }
        return reason.length() > 1000 ? reason.substring(0, 1000) : reason;
    }

    private String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
