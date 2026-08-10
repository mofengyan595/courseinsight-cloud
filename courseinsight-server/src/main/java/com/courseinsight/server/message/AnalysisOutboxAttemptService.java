package com.courseinsight.server.message;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.entity.AnalysisOutboxStatus;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AnalysisOutboxAttemptService {

    private static final List<String> CLAIMABLE_STATUSES = List.of(
            AnalysisOutboxStatus.PENDING.name(),
            AnalysisOutboxStatus.FAILED.name(),
            AnalysisOutboxStatus.PUBLISHING.name()
    );

    private final AnalysisOutboxEventMapper outboxEventMapper;

    public AnalysisOutboxAttemptService(AnalysisOutboxEventMapper outboxEventMapper) {
        this.outboxEventMapper = outboxEventMapper;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<AnalysisOutboxEvent> findPublishable(
            int batchSize,
            LocalDateTime now) {
        LambdaQueryWrapper<AnalysisOutboxEvent> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(AnalysisOutboxEvent::getStatus, CLAIMABLE_STATUSES)
                .le(AnalysisOutboxEvent::getNextRetryAt, now)
                .orderByAsc(AnalysisOutboxEvent::getId)
                .last("LIMIT " + batchSize);
        return outboxEventMapper.selectList(wrapper);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(
            Long outboxId,
            String publishToken,
            LocalDateTime now,
            long recoveryTimeoutSeconds) {
        LambdaUpdateWrapper<AnalysisOutboxEvent> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AnalysisOutboxEvent::getId, outboxId)
                .in(AnalysisOutboxEvent::getStatus, CLAIMABLE_STATUSES)
                .le(AnalysisOutboxEvent::getNextRetryAt, now)
                .set(AnalysisOutboxEvent::getStatus, AnalysisOutboxStatus.PUBLISHING.name())
                .set(AnalysisOutboxEvent::getPublishToken, publishToken)
                .set(AnalysisOutboxEvent::getFailureReason, null)
                .set(AnalysisOutboxEvent::getNextRetryAt,
                        now.plusSeconds(recoveryTimeoutSeconds));
        return outboxEventMapper.update(null, wrapper) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSent(
            Long outboxId,
            String publishToken,
            String messageId,
            LocalDateTime sentAt) {
        LambdaUpdateWrapper<AnalysisOutboxEvent> wrapper = ownerUpdate(
                outboxId,
                publishToken
        );
        wrapper.set(AnalysisOutboxEvent::getStatus, AnalysisOutboxStatus.SENT.name())
                .set(AnalysisOutboxEvent::getPublishToken, null)
                .set(AnalysisOutboxEvent::getMessageId, messageId)
                .set(AnalysisOutboxEvent::getFailureReason, null)
                .set(AnalysisOutboxEvent::getSentAt, sentAt);
        return outboxEventMapper.update(null, wrapper) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(
            Long outboxId,
            String publishToken,
            String reason,
            LocalDateTime retryAt) {
        LambdaUpdateWrapper<AnalysisOutboxEvent> wrapper = ownerUpdate(
                outboxId,
                publishToken
        );
        wrapper.set(AnalysisOutboxEvent::getStatus, AnalysisOutboxStatus.FAILED.name())
                .set(AnalysisOutboxEvent::getPublishToken, null)
                .set(AnalysisOutboxEvent::getFailureReason, reason)
                .set(AnalysisOutboxEvent::getNextRetryAt, retryAt)
                .setSql("retry_count = retry_count + 1");
        return outboxEventMapper.update(null, wrapper) == 1;
    }

    private LambdaUpdateWrapper<AnalysisOutboxEvent> ownerUpdate(
            Long outboxId,
            String publishToken) {
        LambdaUpdateWrapper<AnalysisOutboxEvent> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AnalysisOutboxEvent::getId, outboxId)
                .eq(AnalysisOutboxEvent::getStatus,
                        AnalysisOutboxStatus.PUBLISHING.name())
                .eq(AnalysisOutboxEvent::getPublishToken, publishToken);
        return wrapper;
    }
}
