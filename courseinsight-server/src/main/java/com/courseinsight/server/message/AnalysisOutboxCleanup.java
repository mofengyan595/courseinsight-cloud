package com.courseinsight.server.message;

import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "courseinsight.outbox.cleanup.enabled", havingValue = "true")
public class AnalysisOutboxCleanup {

    private static final Logger log = LoggerFactory.getLogger(AnalysisOutboxCleanup.class);

    private final AnalysisOutboxEventMapper mapper;
    private final int retentionDays;
    private final int batchSize;

    public AnalysisOutboxCleanup(
            AnalysisOutboxEventMapper mapper,
            @Value("${courseinsight.outbox.cleanup.retention-days:30}") int retentionDays,
            @Value("${courseinsight.outbox.cleanup.batch-size:500}") int batchSize) {
        if (retentionDays < 1 || batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Outbox retention must be positive; cleanup batch must be 1..1000");
        }
        this.mapper = mapper;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
    }

    @Scheduled(
            scheduler = "outboxCleanupScheduler",
            fixedDelayString = "${courseinsight.outbox.cleanup.interval-ms:3600000}",
            initialDelayString = "${courseinsight.outbox.cleanup.initial-delay-ms:3600000}"
    )
    @Transactional
    public int cleanupSent() {
        int deleted = mapper.deleteExpiredSent(retentionDays, batchSize);
        if (deleted > 0) {
            log.info("Deleted {} SENT Outbox events older than {} days", deleted, retentionDays);
        }
        return deleted;
    }
}
