package com.courseinsight.server.metrics;

import com.courseinsight.server.exception.RetryableAiServiceException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourseInsightMetricsTests {

    @Test
    void shouldRecordAiLatencyWithBoundedOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CourseInsightMetrics metrics = new CourseInsightMetrics(registry);

        assertThat(metrics.recordAiRequest(() -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> metrics.recordAiRequest(() -> {
            throw new RetryableAiServiceException("temporary", null);
        })).isInstanceOf(RetryableAiServiceException.class);
        assertThatThrownBy(() -> metrics.recordAiRequest(() -> {
            throw new IllegalStateException("terminal");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(timerCount(registry, "success")).isEqualTo(1);
        assertThat(timerCount(registry, "retryable_failure")).isEqualTo(1);
        assertThat(timerCount(registry, "terminal_failure")).isEqualTo(1);
    }

    @Test
    void shouldRecordAnalysisTaskAndOutboxCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CourseInsightMetrics metrics = new CourseInsightMetrics(registry);

        metrics.analysisTaskSucceeded();
        metrics.analysisTaskFailed(false);
        metrics.analysisTaskFailed(true);
        metrics.analysisTaskLeaseRecovered(2);
        metrics.analysisTaskDeadLettered();
        metrics.outboxPublishSucceeded();
        metrics.outboxPublishFailed();

        assertThat(counterValue(
                registry,
                CourseInsightMetrics.ANALYSIS_TASK_METRIC,
                "success"
        )).isEqualTo(1);
        assertThat(counterValue(
                registry,
                CourseInsightMetrics.ANALYSIS_TASK_METRIC,
                "retryable_failure"
        )).isEqualTo(1);
        assertThat(counterValue(
                registry,
                CourseInsightMetrics.ANALYSIS_TASK_METRIC,
                "terminal_failure"
        )).isEqualTo(1);
        assertThat(counterValue(
                registry,
                CourseInsightMetrics.ANALYSIS_TASK_METRIC,
                "lease_recovery"
        )).isEqualTo(2);
        assertThat(counterValue(
                registry,
                CourseInsightMetrics.ANALYSIS_TASK_METRIC,
                "dlq_terminal"
        )).isEqualTo(1);
        assertThat(counterValue(
                registry,
                CourseInsightMetrics.OUTBOX_PUBLISH_METRIC,
                "success"
        )).isEqualTo(1);
        assertThat(counterValue(
                registry,
                CourseInsightMetrics.OUTBOX_PUBLISH_METRIC,
                "failure"
        )).isEqualTo(1);
    }

    @Test
    void shouldRecordOutboxSendLatencyAndBoundedConcurrencyGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CourseInsightMetrics metrics = new CourseInsightMetrics(registry);
        metrics.configureOutboxPublishConcurrency(2);

        assertThat(metrics.recordOutboxSend(() -> "message-id"))
                .isEqualTo("message-id");
        assertThatThrownBy(() -> metrics.recordOutboxSend(() -> {
            throw new IllegalStateException("send failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(registry.get(CourseInsightMetrics.OUTBOX_SEND_METRIC)
                .tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(registry.get(CourseInsightMetrics.OUTBOX_SEND_METRIC)
                .tag("outcome", "failure").timer().count()).isEqualTo(1);
        assertThat(registry.get(
                "courseinsight.outbox.publish.configured.concurrency"
        ).gauge().value()).isEqualTo(2);
        assertThat(registry.get(
                "courseinsight.outbox.publish.active"
        ).gauge().value()).isZero();
        assertThat(registry.get(
                "courseinsight.outbox.publish.peak.active"
        ).gauge().value()).isEqualTo(1);
    }

    private long timerCount(SimpleMeterRegistry registry, String outcome) {
        return registry.get(CourseInsightMetrics.AI_REQUEST_METRIC)
                .tag("outcome", outcome)
                .timer()
                .count();
    }

    private double counterValue(
            SimpleMeterRegistry registry,
            String name,
            String outcome) {
        return registry.get(name)
                .tag("outcome", outcome)
                .counter()
                .count();
    }
}
