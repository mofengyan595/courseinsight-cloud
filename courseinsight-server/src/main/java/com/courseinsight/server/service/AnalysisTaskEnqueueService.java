package com.courseinsight.server.service;

import com.courseinsight.server.dto.AnalysisTaskEnqueueResponse;
import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.entity.AnalysisOutboxStatus;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.AnalysisTaskStatus;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.AnalysisTaskConflictException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.message.AnalysisTaskCreatedEvent;
import com.courseinsight.server.ratelimit.RateLimitPolicy;
import com.courseinsight.server.ratelimit.RedisRateLimiter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AnalysisTaskEnqueueService {

    private final AnalysisTaskMapper analysisTaskMapper;
    private final AnalysisOutboxEventMapper outboxEventMapper;
    private final CourseManagementAccessService managementAccessService;
    private final RedisRateLimiter rateLimiter;

    public AnalysisTaskEnqueueService(
            AnalysisTaskMapper analysisTaskMapper,
            AnalysisOutboxEventMapper outboxEventMapper,
            CourseManagementAccessService managementAccessService,
            RedisRateLimiter rateLimiter) {
        this.analysisTaskMapper = analysisTaskMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.managementAccessService = managementAccessService;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public AnalysisTaskEnqueueResponse enqueue(
            Long taskId,
            Long currentUserId,
            UserRole currentRole) {
        rateLimiter.check(RateLimitPolicy.MANUAL_ANALYSIS, currentUserId);
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            throw new ResourceNotFoundException("分析任务不存在");
        }
        managementAccessService.assertCanManage(
                task.getCourseId(),
                currentUserId,
                currentRole
        );
        if (AnalysisTaskStatus.SUCCESS.name().equals(task.getStatus())) {
            throw new AnalysisTaskConflictException(
                    "分析任务已经完成，无需重复入队"
            );
        }
        if (AnalysisTaskStatus.PROCESSING.name().equals(task.getStatus())) {
            throw new AnalysisTaskConflictException(
                    "分析任务正在处理中"
            );
        }
        if (task.getBatchId() != null) {
            throw new AnalysisTaskConflictException(
                    "批量分析任务请使用批次失败重试接口"
            );
        }

        String eventId = randomId();
        if (analysisTaskMapper.requeueWithNewGeneration(
                taskId,
                task.getCurrentEventId(),
                eventId
        ) != 1) {
            throw new AnalysisTaskConflictException(
                    "分析任务状态已变化，无法创建新的入队代次"
            );
        }

        AnalysisOutboxEvent outboxEvent = new AnalysisOutboxEvent();
        outboxEvent.setEventId(eventId);
        outboxEvent.setTaskId(task.getId());
        outboxEvent.setCommentId(task.getCommentId());
        outboxEvent.setEventType(AnalysisTaskCreatedEvent.EVENT_TYPE);
        outboxEvent.setStatus(AnalysisOutboxStatus.PENDING.name());
        outboxEvent.setRetryCount(0);
        outboxEvent.setNextRetryAt(LocalDateTime.now());
        if (outboxEventMapper.insert(outboxEvent) != 1) {
            throw new IllegalStateException("分析任务 Outbox 事件创建失败");
        }

        return new AnalysisTaskEnqueueResponse(
                eventId,
                task.getId(),
                task.getCommentId(),
                null
        );
    }

    private String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
