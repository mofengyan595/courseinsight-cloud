package com.courseinsight.server.metrics;

import com.courseinsight.server.exception.RetryableAiServiceException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class CourseInsightMetrics {

    static final String AI_REQUEST_METRIC = "courseinsight.ai.request";
    static final String ANALYSIS_TASK_METRIC = "courseinsight.analysis.task";
    static final String OUTBOX_PUBLISH_METRIC = "courseinsight.outbox.publish";

    private final MeterRegistry meterRegistry;
    private final Map<AiRequestOutcome, Timer> aiRequestTimers;
    private final Map<AnalysisTaskOutcome, Counter> analysisTaskCounters;
    private final Map<OutboxPublishOutcome, Counter> outboxPublishCounters;

    public CourseInsightMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.aiRequestTimers = registerAiRequestTimers(meterRegistry);
        this.analysisTaskCounters = registerAnalysisTaskCounters(meterRegistry);
        this.outboxPublishCounters = registerOutboxPublishCounters(meterRegistry);
    }

    public <T> T recordAiRequest(Supplier<T> request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        AiRequestOutcome outcome = AiRequestOutcome.TERMINAL_FAILURE;
        try {
            T result = request.get();
            outcome = AiRequestOutcome.SUCCESS;
            return result;
        } catch (RetryableAiServiceException exception) {
            outcome = AiRequestOutcome.RETRYABLE_FAILURE;
            throw exception;
        } finally {
            sample.stop(aiRequestTimers.get(outcome));
        }
    }

    public void analysisTaskSucceeded() {
        analysisTaskCounters.get(AnalysisTaskOutcome.SUCCESS).increment();
    }

    public void analysisTaskFailed(boolean terminal) {
        AnalysisTaskOutcome outcome = terminal
                ? AnalysisTaskOutcome.TERMINAL_FAILURE
                : AnalysisTaskOutcome.RETRYABLE_FAILURE;
        analysisTaskCounters.get(outcome).increment();
    }

    public void analysisTaskLeaseRecovered(int recovered) {
        if (recovered > 0) {
            analysisTaskCounters.get(AnalysisTaskOutcome.LEASE_RECOVERY)
                    .increment(recovered);
        }
    }

    public void analysisTaskDeadLettered() {
        analysisTaskCounters.get(AnalysisTaskOutcome.DLQ_TERMINAL).increment();
    }

    public void outboxPublishSucceeded() {
        outboxPublishCounters.get(OutboxPublishOutcome.SUCCESS).increment();
    }

    public void outboxPublishFailed() {
        outboxPublishCounters.get(OutboxPublishOutcome.FAILURE).increment();
    }

    private Map<AiRequestOutcome, Timer> registerAiRequestTimers(
            MeterRegistry registry) {
        Map<AiRequestOutcome, Timer> timers = new EnumMap<>(AiRequestOutcome.class);
        for (AiRequestOutcome outcome : AiRequestOutcome.values()) {
            timers.put(
                    outcome,
                    Timer.builder(AI_REQUEST_METRIC)
                            .description("AI analysis request latency and outcome")
                            .tag("outcome", outcome.tagValue)
                            .register(registry)
            );
        }
        return timers;
    }

    private Map<AnalysisTaskOutcome, Counter> registerAnalysisTaskCounters(
            MeterRegistry registry) {
        Map<AnalysisTaskOutcome, Counter> counters =
                new EnumMap<>(AnalysisTaskOutcome.class);
        for (AnalysisTaskOutcome outcome : AnalysisTaskOutcome.values()) {
            counters.put(
                    outcome,
                    Counter.builder(ANALYSIS_TASK_METRIC)
                            .description("Analysis task lifecycle events")
                            .tag("outcome", outcome.tagValue)
                            .register(registry)
            );
        }
        return counters;
    }

    private Map<OutboxPublishOutcome, Counter> registerOutboxPublishCounters(
            MeterRegistry registry) {
        Map<OutboxPublishOutcome, Counter> counters =
                new EnumMap<>(OutboxPublishOutcome.class);
        for (OutboxPublishOutcome outcome : OutboxPublishOutcome.values()) {
            counters.put(
                    outcome,
                    Counter.builder(OUTBOX_PUBLISH_METRIC)
                            .description("Analysis Outbox publish attempts")
                            .tag("outcome", outcome.tagValue)
                            .register(registry)
            );
        }
        return counters;
    }

    private enum AiRequestOutcome {
        SUCCESS("success"),
        RETRYABLE_FAILURE("retryable_failure"),
        TERMINAL_FAILURE("terminal_failure");

        private final String tagValue;

        AiRequestOutcome(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    private enum AnalysisTaskOutcome {
        SUCCESS("success"),
        RETRYABLE_FAILURE("retryable_failure"),
        TERMINAL_FAILURE("terminal_failure"),
        LEASE_RECOVERY("lease_recovery"),
        DLQ_TERMINAL("dlq_terminal");

        private final String tagValue;

        AnalysisTaskOutcome(String tagValue) {
            this.tagValue = tagValue;
        }
    }

    private enum OutboxPublishOutcome {
        SUCCESS("success"),
        FAILURE("failure");

        private final String tagValue;

        OutboxPublishOutcome(String tagValue) {
            this.tagValue = tagValue;
        }
    }
}
