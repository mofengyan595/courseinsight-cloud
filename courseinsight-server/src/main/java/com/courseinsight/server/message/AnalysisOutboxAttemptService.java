package com.courseinsight.server.message;

import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AnalysisOutboxAttemptService {

    private final AnalysisOutboxEventMapper outboxEventMapper;

    public AnalysisOutboxAttemptService(AnalysisOutboxEventMapper outboxEventMapper) {
        this.outboxEventMapper = outboxEventMapper;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<AnalysisOutboxEvent> findPublishable(int batchSize) {
        return outboxEventMapper.selectPublishable(batchSize);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(
            Long outboxId,
            String publishToken,
            long recoveryTimeoutSeconds) {
        return outboxEventMapper.claimPublishAttempt(
                outboxId,
                publishToken,
                recoveryTimeoutSeconds
        ) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSent(
            Long outboxId,
            String publishToken,
            String messageId) {
        return outboxEventMapper.markOwnedAttemptSent(
                outboxId,
                publishToken,
                messageId
        ) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(
            Long outboxId,
            String publishToken,
            String reason,
            long retryDelaySeconds) {
        return outboxEventMapper.markOwnedAttemptFailed(
                outboxId,
                publishToken,
                reason,
                retryDelaySeconds
        ) == 1;
    }
}
