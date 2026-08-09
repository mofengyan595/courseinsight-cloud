package com.courseinsight.server.message;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.entity.AnalysisOutboxStatus;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import com.courseinsight.server.metrics.CourseInsightMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Component
@ConditionalOnProperty(
        name = "courseinsight.outbox.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AnalysisOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(AnalysisOutboxPublisher.class);

    private final AnalysisOutboxEventMapper outboxEventMapper;
    private final AnalysisTaskMessageProducer messageProducer;
    private final int batchSize;
    private final long retryDelaySeconds;
    private final long recoveryTimeoutSeconds;
    private final CourseInsightMetrics metrics;

    public AnalysisOutboxPublisher(
            AnalysisOutboxEventMapper outboxEventMapper,
            AnalysisTaskMessageProducer messageProducer,
            @Value("${courseinsight.outbox.batch-size:20}") int batchSize,
            @Value("${courseinsight.outbox.retry-delay-seconds:30}") long retryDelaySeconds,
            @Value("${courseinsight.outbox.recovery-timeout-seconds:300}") long recoveryTimeoutSeconds,
            CourseInsightMetrics metrics) {
        this.outboxEventMapper = outboxEventMapper;
        this.messageProducer = messageProducer;
        this.batchSize = batchSize;
        this.retryDelaySeconds = retryDelaySeconds;
        this.recoveryTimeoutSeconds = recoveryTimeoutSeconds;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${courseinsight.outbox.publish-interval-ms:1000}",
            initialDelayString = "${courseinsight.outbox.initial-delay-ms:1000}"
    )
    public void publishPending() {
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<AnalysisOutboxEvent> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(AnalysisOutboxEvent::getStatus, List.of(
                        AnalysisOutboxStatus.PENDING.name(),
                        AnalysisOutboxStatus.FAILED.name(),
                        AnalysisOutboxStatus.PUBLISHING.name()
                ))
                .le(AnalysisOutboxEvent::getNextRetryAt, now)
                .orderByAsc(AnalysisOutboxEvent::getId)
                .last("LIMIT " + batchSize);

        for (AnalysisOutboxEvent event : outboxEventMapper.selectList(wrapper)) {
            publishOne(event, now);
        }
    }

    private void publishOne(AnalysisOutboxEvent event, LocalDateTime now) {
        Long outboxId = event.getId();
        if (!claim(outboxId, now)) {
            return;
        }

        try {
            AnalysisTaskCreatedEvent message = new AnalysisTaskCreatedEvent(
                    event.getEventId(),
                    event.getTaskId(),
                    event.getCommentId(),
                    event.getEventType(),
                    Instant.now().toString()
            );
            String messageId = messageProducer.send(message);
            markSent(outboxId, messageId);
            metrics.outboxPublishSucceeded();
        } catch (RuntimeException exception) {
            markFailed(outboxId, event.getEventId(), exception);
            metrics.outboxPublishFailed();
        }
    }

    private boolean claim(Long outboxId, LocalDateTime now) {
        LambdaUpdateWrapper<AnalysisOutboxEvent> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AnalysisOutboxEvent::getId, outboxId)
                .in(AnalysisOutboxEvent::getStatus, List.of(
                        AnalysisOutboxStatus.PENDING.name(),
                        AnalysisOutboxStatus.FAILED.name(),
                        AnalysisOutboxStatus.PUBLISHING.name()
                ))
                .le(AnalysisOutboxEvent::getNextRetryAt, now)
                .set(AnalysisOutboxEvent::getStatus, AnalysisOutboxStatus.PUBLISHING.name())
                .set(AnalysisOutboxEvent::getFailureReason, null)
                .set(AnalysisOutboxEvent::getNextRetryAt,
                        now.plusSeconds(recoveryTimeoutSeconds));
        return outboxEventMapper.update(null, wrapper) == 1;
    }

    private void markSent(Long outboxId, String messageId) {
        LambdaUpdateWrapper<AnalysisOutboxEvent> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AnalysisOutboxEvent::getId, outboxId)
                .eq(AnalysisOutboxEvent::getStatus, AnalysisOutboxStatus.PUBLISHING.name())
                .set(AnalysisOutboxEvent::getStatus, AnalysisOutboxStatus.SENT.name())
                .set(AnalysisOutboxEvent::getMessageId, messageId)
                .set(AnalysisOutboxEvent::getFailureReason, null)
                .set(AnalysisOutboxEvent::getSentAt, LocalDateTime.now());
        if (outboxEventMapper.update(null, wrapper) != 1) {
            throw new IllegalStateException("Outbox 事件发布状态更新失败");
        }
    }

    private void markFailed(
            Long outboxId,
            String eventId,
            RuntimeException originalException) {
        String reason = failureReason(originalException);
        LambdaUpdateWrapper<AnalysisOutboxEvent> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AnalysisOutboxEvent::getId, outboxId)
                .eq(AnalysisOutboxEvent::getStatus, AnalysisOutboxStatus.PUBLISHING.name())
                .set(AnalysisOutboxEvent::getStatus, AnalysisOutboxStatus.FAILED.name())
                .set(AnalysisOutboxEvent::getFailureReason, reason)
                .set(AnalysisOutboxEvent::getNextRetryAt,
                        LocalDateTime.now().plusSeconds(retryDelaySeconds))
                .setSql("retry_count = retry_count + 1");
        try {
            outboxEventMapper.update(null, wrapper);
        } catch (RuntimeException updateException) {
            originalException.addSuppressed(updateException);
        }
        log.error(
                "Outbox 事件发布失败, outboxId={}, eventId={}, reason={}",
                outboxId,
                eventId,
                reason,
                originalException);
    }

    private String failureReason(RuntimeException exception) {
        String reason = exception.getMessage();
        if (reason == null || reason.isBlank()) {
            reason = exception.getClass().getSimpleName();
        }
        return reason.length() > 1000 ? reason.substring(0, 1000) : reason;
    }
}
