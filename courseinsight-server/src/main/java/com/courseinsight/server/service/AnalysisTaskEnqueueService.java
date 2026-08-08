package com.courseinsight.server.service;

import com.courseinsight.server.dto.AnalysisTaskEnqueueResponse;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.AnalysisTaskStatus;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.AnalysisTaskConflictException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.message.AnalysisTaskCreatedEvent;
import com.courseinsight.server.message.AnalysisTaskMessageProducer;
import com.courseinsight.server.ratelimit.RateLimitPolicy;
import com.courseinsight.server.ratelimit.RedisRateLimiter;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class AnalysisTaskEnqueueService {

    private final AnalysisTaskMapper analysisTaskMapper;
    private final AnalysisTaskMessageProducer messageProducer;
    private final CourseManagementAccessService managementAccessService;
    private final RedisRateLimiter rateLimiter;

    public AnalysisTaskEnqueueService(
            AnalysisTaskMapper analysisTaskMapper,
            AnalysisTaskMessageProducer messageProducer,
            CourseManagementAccessService managementAccessService,
            RedisRateLimiter rateLimiter) {
        this.analysisTaskMapper = analysisTaskMapper;
        this.messageProducer = messageProducer;
        this.managementAccessService = managementAccessService;
        this.rateLimiter = rateLimiter;
    }

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
            throw new AnalysisTaskConflictException("分析任务已经完成，无需重复入队");
        }
        if (AnalysisTaskStatus.PROCESSING.name().equals(task.getStatus())) {
            throw new AnalysisTaskConflictException("分析任务正在处理中");
        }
        if (task.getBatchId() != null) {
            throw new AnalysisTaskConflictException(
                    "批量分析任务请使用批次失败重试接口"
            );
        }

        AnalysisTaskCreatedEvent event = new AnalysisTaskCreatedEvent(
                UUID.randomUUID().toString().replace("-", ""),
                task.getId(),
                task.getCommentId(),
                AnalysisTaskCreatedEvent.EVENT_TYPE,
                Instant.now().toString()
        );
        String messageId = messageProducer.send(event);

        return new AnalysisTaskEnqueueResponse(
                event.eventId(),
                event.taskId(),
                event.commentId(),
                messageId
        );
    }
}
