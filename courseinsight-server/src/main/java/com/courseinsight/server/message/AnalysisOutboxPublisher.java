package com.courseinsight.server.message;

import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.metrics.CourseInsightMetrics;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(
        name = "courseinsight.outbox.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AnalysisOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(AnalysisOutboxPublisher.class);
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final AnalysisOutboxAttemptService attemptService;
    private final AnalysisTaskMessageProducer messageProducer;
    private final int batchSize;
    private final int publishConcurrency;
    private final long retryDelaySeconds;
    private final long recoveryTimeoutSeconds;
    private final long shutdownTimeoutSeconds;
    private final CourseInsightMetrics metrics;
    private final ThreadPoolExecutor publishExecutor;
    private final AtomicBoolean cycleRunning = new AtomicBoolean();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    public AnalysisOutboxPublisher(
            AnalysisOutboxAttemptService attemptService,
            AnalysisTaskMessageProducer messageProducer,
            @Value("${courseinsight.outbox.batch-size:20}") int batchSize,
            @Value("${courseinsight.outbox.publish-concurrency:2}") int publishConcurrency,
            @Value("${courseinsight.outbox.retry-delay-seconds:30}") long retryDelaySeconds,
            @Value("${courseinsight.outbox.recovery-timeout-seconds:300}") long recoveryTimeoutSeconds,
            @Value("${courseinsight.outbox.shutdown-timeout-seconds:30}") long shutdownTimeoutSeconds,
            CourseInsightMetrics metrics) {
        if (batchSize < 1 || publishConcurrency < 1) {
            throw new IllegalArgumentException(
                    "Outbox batch size and publish concurrency must be positive"
            );
        }
        this.attemptService = attemptService;
        this.messageProducer = messageProducer;
        this.batchSize = batchSize;
        this.publishConcurrency = publishConcurrency;
        this.retryDelaySeconds = retryDelaySeconds;
        this.recoveryTimeoutSeconds = recoveryTimeoutSeconds;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
        this.metrics = metrics;
        this.publishExecutor = createExecutor(publishConcurrency);
        this.metrics.configureOutboxPublishConcurrency(publishConcurrency);
    }

    @Scheduled(
            fixedDelayString = "${courseinsight.outbox.publish-interval-ms:1000}",
            initialDelayString = "${courseinsight.outbox.initial-delay-ms:1000}"
    )
    public void publishPending() {
        if (shuttingDown.get() || !cycleRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            LocalDateTime selectedAt = LocalDateTime.now();
            publishBatch(attemptService.findPublishable(batchSize, selectedAt));
        } finally {
            cycleRunning.set(false);
        }
    }

    private void publishBatch(List<AnalysisOutboxEvent> events) {
        ExecutorCompletionService<Void> completions =
                new ExecutorCompletionService<>(publishExecutor);
        int submitted = 0;
        int completed = 0;
        while (completed < events.size() && !Thread.currentThread().isInterrupted()) {
            while (submitted < events.size()
                    && submitted - completed < publishConcurrency
                    && !shuttingDown.get()) {
                AnalysisOutboxEvent event = events.get(submitted++);
                completions.submit(() -> {
                    publishOne(event);
                    return null;
                });
            }
            if (completed == submitted) {
                return;
            }
            try {
                Future<Void> completedPublish = completions.take();
                completedPublish.get();
                completed++;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException exception) {
                completed++;
                log.error("Unexpected Outbox publisher worker failure", exception.getCause());
            }
        }
    }

    private void publishOne(AnalysisOutboxEvent event) {
        Long outboxId = event.getId();
        String publishToken = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime claimedAt = LocalDateTime.now();
        if (!attemptService.claim(
                outboxId,
                publishToken,
                claimedAt,
                recoveryTimeoutSeconds)) {
            return;
        }

        AnalysisTaskCreatedEvent message = new AnalysisTaskCreatedEvent(
                event.getEventId(),
                event.getTaskId(),
                event.getCommentId(),
                event.getEventType(),
                Instant.now().toString()
        );
        final String messageId;
        try {
            messageId = metrics.recordOutboxSend(() -> messageProducer.send(message));
        } catch (RuntimeException sendException) {
            handleSendFailure(event, publishToken, sendException);
            return;
        }

        try {
            if (attemptService.markSent(
                    outboxId,
                    publishToken,
                    messageId,
                    LocalDateTime.now())) {
                metrics.outboxPublishSucceeded();
            } else {
                log.info(
                        "Ignoring late Outbox publish success, outboxId={}, eventId={}",
                        outboxId,
                        event.getEventId()
                );
            }
        } catch (RuntimeException markException) {
            metrics.outboxPublishFailed();
            log.error(
                    "Broker accepted Outbox event but SENT persistence failed; "
                            + "stale recovery may resend it, outboxId={}, eventId={}",
                    outboxId,
                    event.getEventId(),
                    markException
            );
        }
    }

    private void handleSendFailure(
            AnalysisOutboxEvent event,
            String publishToken,
            RuntimeException sendException) {
        String reason = failureReason(sendException);
        try {
            boolean marked = attemptService.markFailed(
                    event.getId(),
                    publishToken,
                    reason,
                    LocalDateTime.now().plusSeconds(retryDelaySeconds)
            );
            if (!marked) {
                log.info(
                        "Ignoring late Outbox publish failure, outboxId={}, eventId={}",
                        event.getId(),
                        event.getEventId()
                );
                return;
            }
        } catch (RuntimeException updateException) {
            sendException.addSuppressed(updateException);
        }
        metrics.outboxPublishFailed();
        log.error(
                "Outbox event publish failed, outboxId={}, eventId={}, reason={}",
                event.getId(),
                event.getEventId(),
                reason,
                sendException
        );
    }

    private String failureReason(RuntimeException exception) {
        String reason = exception.getMessage();
        if (reason == null || reason.isBlank()) {
            reason = exception.getClass().getSimpleName();
        }
        return reason.length() > 1000 ? reason.substring(0, 1000) : reason;
    }

    @PreDestroy
    void shutdown() {
        shuttingDown.set(true);
        publishExecutor.shutdown();
        try {
            if (!publishExecutor.awaitTermination(
                    shutdownTimeoutSeconds,
                    TimeUnit.SECONDS)) {
                publishExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            publishExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private ThreadPoolExecutor createExecutor(int concurrency) {
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(
                    task,
                    "analysis-outbox-publisher-" + THREAD_SEQUENCE.incrementAndGet()
            );
            thread.setDaemon(false);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(concurrency),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.prestartAllCoreThreads();
        return executor;
    }
}
