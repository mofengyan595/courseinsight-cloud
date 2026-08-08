package com.courseinsight.server.service;

import com.courseinsight.server.dto.AnalysisBatchRetryResponse;
import com.courseinsight.server.entity.AnalysisBatch;
import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.entity.AnalysisOutboxStatus;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisBatchMapper;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.message.AnalysisTaskCreatedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AnalysisBatchRecoveryService {

    private final AnalysisBatchMapper batchMapper;
    private final AnalysisTaskMapper taskMapper;
    private final AnalysisOutboxEventMapper outboxEventMapper;
    private final CourseManagementAccessService accessService;

    public AnalysisBatchRecoveryService(
            AnalysisBatchMapper batchMapper,
            AnalysisTaskMapper taskMapper,
            AnalysisOutboxEventMapper outboxEventMapper,
            CourseManagementAccessService accessService) {
        this.batchMapper = batchMapper;
        this.taskMapper = taskMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.accessService = accessService;
    }

    @Transactional
    public AnalysisBatchRetryResponse retryDeadLettered(
            Long batchId,
            Long currentUserId,
            UserRole currentRole) {
        AnalysisBatch batch = requireBatch(batchId);
        accessService.assertCanManage(
                batch.getCourseId(),
                currentUserId,
                currentRole
        );

        List<AnalysisTask> tasks = taskMapper
                .selectDeadLetteredByBatchIdForUpdate(batchId);
        LocalDateTime requestedAt = LocalDateTime.now();
        int requeuedCount = 0;
        for (AnalysisTask task : tasks) {
            String eventId = randomId();
            if (!markWaiting(task, eventId)) {
                continue;
            }
            if (outboxEventMapper.insert(newOutboxEvent(
                    task,
                    eventId,
                    requestedAt
            )) != 1) {
                throw new IllegalStateException("批量重试 Outbox 事件创建失败");
            }
            requeuedCount++;
        }
        return new AnalysisBatchRetryResponse(
                batchId,
                requeuedCount,
                requestedAt
        );
    }

    private AnalysisBatch requireBatch(Long batchId) {
        AnalysisBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new ResourceNotFoundException("批量分析任务不存在");
        }
        return batch;
    }

    private boolean markWaiting(AnalysisTask task, String eventId) {
        return taskMapper.recoverDeadLetteredWithNewGeneration(
                task.getId(),
                task.getCurrentEventId(),
                eventId
        ) == 1;
    }

    private AnalysisOutboxEvent newOutboxEvent(
            AnalysisTask task,
            String eventId,
            LocalDateTime requestedAt) {
        AnalysisOutboxEvent event = new AnalysisOutboxEvent();
        event.setEventId(eventId);
        event.setTaskId(task.getId());
        event.setCommentId(task.getCommentId());
        event.setEventType(AnalysisTaskCreatedEvent.EVENT_TYPE);
        event.setStatus(AnalysisOutboxStatus.PENDING.name());
        event.setRetryCount(0);
        event.setNextRetryAt(requestedAt);
        return event;
    }

    private String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
