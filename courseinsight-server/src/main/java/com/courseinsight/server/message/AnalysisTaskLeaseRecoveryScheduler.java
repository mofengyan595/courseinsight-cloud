package com.courseinsight.server.message;

import com.courseinsight.server.service.AnalysisTaskLeaseRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "courseinsight.analysis.lease-recovery-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AnalysisTaskLeaseRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(
            AnalysisTaskLeaseRecoveryScheduler.class
    );

    private final AnalysisTaskLeaseRecoveryService recoveryService;

    public AnalysisTaskLeaseRecoveryScheduler(
            AnalysisTaskLeaseRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Scheduled(
            fixedDelayString = "${courseinsight.analysis.lease-recovery-interval-ms:30000}",
            initialDelayString = "${courseinsight.analysis.lease-recovery-interval-ms:30000}"
    )
    public void recoverExpired() {
        int recovered = recoveryService.recoverExpired();
        if (recovered > 0) {
            log.warn("Recovered {} expired analysis task executions", recovered);
        }
    }
}
