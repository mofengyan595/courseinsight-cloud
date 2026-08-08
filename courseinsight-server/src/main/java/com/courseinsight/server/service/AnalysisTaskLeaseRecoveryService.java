package com.courseinsight.server.service;

import com.courseinsight.server.config.AnalysisExecutionProperties;
import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.entity.AnalysisOutboxStatus;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.message.AnalysisTaskCreatedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AnalysisTaskLeaseRecoveryService {

    private final AnalysisTaskMapper taskMapper;
    private final AnalysisOutboxEventMapper outboxEventMapper;
    private final AnalysisExecutionProperties properties;

    public AnalysisTaskLeaseRecoveryService(
            AnalysisTaskMapper taskMapper,
            AnalysisOutboxEventMapper outboxEventMapper,
            AnalysisExecutionProperties properties) {
        this.taskMapper = taskMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.properties = properties;
    }

    @Transactional
    public int recoverExpired() {
        LocalDateTime now = LocalDateTime.now();
        int recovered = 0;
        for (AnalysisTask task : taskMapper.selectExpiredExecutionsForUpdate(
                now,
                properties.leaseRecoveryBatchSize()
        )) {
            String eventId = randomId();
            if (taskMapper.recoverExpiredExecution(
                    task.getId(),
                    task.getCurrentEventId(),
                    task.getExecutionToken(),
                    eventId,
                    now
            ) != 1) {
                continue;
            }

            AnalysisOutboxEvent outboxEvent = new AnalysisOutboxEvent();
            outboxEvent.setEventId(eventId);
            outboxEvent.setTaskId(task.getId());
            outboxEvent.setCommentId(task.getCommentId());
            outboxEvent.setEventType(AnalysisTaskCreatedEvent.EVENT_TYPE);
            outboxEvent.setStatus(AnalysisOutboxStatus.PENDING.name());
            outboxEvent.setRetryCount(0);
            outboxEvent.setNextRetryAt(now);
            if (outboxEventMapper.insert(outboxEvent) != 1) {
                throw new IllegalStateException(
                        "Failed to create lease-recovery Outbox event"
                );
            }
            recovered++;
        }
        return recovered;
    }

    private String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
