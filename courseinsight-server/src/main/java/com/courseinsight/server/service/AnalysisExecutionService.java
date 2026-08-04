package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.courseinsight.server.cache.CourseAnalyticsCache;
import com.courseinsight.server.client.AiAnalysisClient;
import com.courseinsight.server.client.AiAnalysisResponse;
import com.courseinsight.server.dto.AnalysisExecutionResponse;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.AnalysisTaskStatus;
import com.courseinsight.server.entity.CourseComment;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.AnalysisTaskConflictException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.mapper.CourseCommentMapper;
import com.courseinsight.server.ratelimit.RateLimitPolicy;
import com.courseinsight.server.ratelimit.RedisRateLimiter;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AnalysisExecutionService {

    private final AnalysisTaskMapper analysisTaskMapper;
    private final CourseCommentMapper courseCommentMapper;
    private final AiAnalysisClient aiAnalysisClient;
    private final AnalysisResultPersistenceService persistenceService;
    private final CourseManagementAccessService managementAccessService;
    private final CourseAnalyticsCache courseAnalyticsCache;
    private final RedisRateLimiter rateLimiter;

    public AnalysisExecutionService(
            AnalysisTaskMapper analysisTaskMapper,
            CourseCommentMapper courseCommentMapper,
            AiAnalysisClient aiAnalysisClient,
            AnalysisResultPersistenceService persistenceService,
            CourseManagementAccessService managementAccessService,
            CourseAnalyticsCache courseAnalyticsCache,
            RedisRateLimiter rateLimiter) {
        this.analysisTaskMapper = analysisTaskMapper;
        this.courseCommentMapper = courseCommentMapper;
        this.aiAnalysisClient = aiAnalysisClient;
        this.persistenceService = persistenceService;
        this.managementAccessService = managementAccessService;
        this.courseAnalyticsCache = courseAnalyticsCache;
        this.rateLimiter = rateLimiter;
    }

    public AnalysisExecutionResponse execute(Long taskId) {
        AnalysisTask task = requireTask(taskId);
        return executeTask(task);
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
        return executeTask(task);
    }

    private AnalysisExecutionResponse executeTask(AnalysisTask task) {
        Long taskId = task.getId();
        if (AnalysisTaskStatus.SUCCESS.name().equals(task.getStatus())) {
            return persistenceService.getSuccessResult(taskId);
        }
        if (AnalysisTaskStatus.PROCESSING.name().equals(task.getStatus())) {
            throw new AnalysisTaskConflictException("分析任务正在处理中");
        }

        CourseComment comment = courseCommentMapper.selectById(task.getCommentId());
        if (comment == null) {
            throw new ResourceNotFoundException("分析任务对应的课程评价不存在");
        }

        claimTask(taskId);
        courseAnalyticsCache.evict(task.getCourseId());
        try {
            AiAnalysisResponse response = aiAnalysisClient.analyze(
                    task.getId(),
                    task.getCommentId(),
                    comment.getContent(),
                    true
            );
            return persistenceService.saveSuccess(task, response);
        } catch (RuntimeException exception) {
            markFailed(taskId, task.getCourseId(), exception);
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

    private void claimTask(Long taskId) {
        LambdaUpdateWrapper<AnalysisTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AnalysisTask::getId, taskId)
                .in(AnalysisTask::getStatus, List.of(
                        AnalysisTaskStatus.WAITING.name(),
                        AnalysisTaskStatus.FAILED.name()
                ))
                .set(AnalysisTask::getStatus, AnalysisTaskStatus.PROCESSING.name())
                .set(AnalysisTask::getFailureReason, null)
                .set(AnalysisTask::getStartedAt, LocalDateTime.now())
                .set(AnalysisTask::getCompletedAt, null)
                .set(AnalysisTask::getDeadLetteredAt, null);

        if (analysisTaskMapper.update(null, wrapper) != 1) {
            throw new AnalysisTaskConflictException("分析任务已被其他请求处理");
        }
    }

    private void markFailed(
            Long taskId,
            Long courseId,
            RuntimeException originalException) {
        String reason = originalException.getMessage();
        if (reason == null || reason.isBlank()) {
            reason = originalException.getClass().getSimpleName();
        }
        if (reason.length() > 1000) {
            reason = reason.substring(0, 1000);
        }

        LambdaUpdateWrapper<AnalysisTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AnalysisTask::getId, taskId)
                .eq(AnalysisTask::getStatus, AnalysisTaskStatus.PROCESSING.name())
                .set(AnalysisTask::getStatus, AnalysisTaskStatus.FAILED.name())
                .set(AnalysisTask::getFailureReason, reason)
                .set(AnalysisTask::getCompletedAt, LocalDateTime.now())
                .setSql("retry_count = retry_count + 1");

        try {
            if (analysisTaskMapper.update(null, wrapper) == 1) {
                courseAnalyticsCache.evict(courseId);
            }
        } catch (RuntimeException failureUpdateException) {
            originalException.addSuppressed(failureUpdateException);
        }
    }
}
