package com.courseinsight.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "courseinsight.analysis")
public record AnalysisExecutionProperties(
        Duration executionLease,
        int leaseRecoveryBatchSize
) {

    public AnalysisExecutionProperties {
        if (executionLease == null) {
            executionLease = Duration.ofMinutes(3);
        }
        if (executionLease.isZero() || executionLease.isNegative()) {
            throw new IllegalArgumentException("executionLease must be positive");
        }
        if (leaseRecoveryBatchSize <= 0) {
            leaseRecoveryBatchSize = 20;
        }
        executionLeaseMicros(executionLease);
    }

    public long executionLeaseMicros() {
        return executionLeaseMicros(executionLease);
    }

    private static long executionLeaseMicros(Duration duration) {
        long seconds = Math.multiplyExact(duration.getSeconds(), 1_000_000L);
        long micros = Math.addExact(seconds, duration.getNano() / 1_000L);
        if (micros <= 0) {
            throw new IllegalArgumentException(
                    "executionLease must be at least one microsecond"
            );
        }
        return micros;
    }
}
