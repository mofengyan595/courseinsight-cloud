package com.courseinsight.server.message;

import com.courseinsight.server.metrics.CourseInsightMetrics;
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
    private final CourseInsightMetrics metrics;

    public AnalysisTaskLeaseRecoveryScheduler(
            AnalysisTaskLeaseRecoveryService recoveryService,
            CourseInsightMetrics metrics) {
        this.recoveryService = recoveryService;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString = "${courseinsight.analysis.lease-recovery-interval-ms:30000}",
            initialDelayString = "${courseinsight.analysis.lease-recovery-interval-ms:30000}"
    )
    public void recoverExpired() {
        int recovered = recoveryService.recoverExpired();
        metrics.analysisTaskLeaseRecovered(recovered);
        if (recovered > 0) {
            log.warn("Recovered {} expired analysis task executions", recovered);
        }
    }
}
