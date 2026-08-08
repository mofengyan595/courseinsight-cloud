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
    }
}
