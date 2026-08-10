package com.courseinsight.server;

import com.courseinsight.server.message.AnalysisOutboxAttemptService;
import com.courseinsight.server.message.AnalysisOutboxPublisher;
import com.courseinsight.server.message.AnalysisTaskCreatedEvent;
import com.courseinsight.server.message.AnalysisTaskMessageProducer;
import com.courseinsight.server.metrics.CourseInsightMetrics;
import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@MySqlIntegrationTest
@TestPropertySource(properties = "courseinsight.outbox.enabled=false")
class AnalysisOutboxAttemptFencingIntegrationTests {

    @Autowired
    private AnalysisOutboxAttemptService attemptService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void lateSuccessCannotCompleteNewPublishAttempt() {
        TestOutbox outbox = insertPendingOutbox();
        try {
            assertThat(attemptService.claim(
                    outbox.id(), "attempt-a", 0)).isTrue();
            assertThat(attemptService.claim(
                    outbox.id(), "attempt-b", 300)).isTrue();

            assertThat(attemptService.markSent(
                    outbox.id(),
                    "attempt-a",
                    "stale-message"
            )).isFalse();
            assertOwner(outbox.id(), "PUBLISHING", "attempt-b", 0);
            assertThat(attemptService.markSent(
                    outbox.id(),
                    "attempt-b",
                    "current-message"
            )).isTrue();
            assertOwner(outbox.id(), "SENT", null, 0);
        } finally {
            deleteOutbox(outbox);
        }
    }

    @Test
    void lateFailureCannotFailNewPublishAttempt() {
        TestOutbox outbox = insertPendingOutbox();
        try {
            assertThat(attemptService.claim(
                    outbox.id(), "attempt-a", 0)).isTrue();
            assertThat(attemptService.claim(
                    outbox.id(), "attempt-b", 300)).isTrue();

            assertThat(attemptService.markFailed(
                    outbox.id(),
                    "attempt-a",
                    "late failure",
                    30
            )).isFalse();
            assertOwner(outbox.id(), "PUBLISHING", "attempt-b", 0);
        } finally {
            deleteOutbox(outbox);
        }
    }

    @Test
    void failedCurrentAttemptSchedulesRetryAndIncrementsCount() {
        TestOutbox outbox = insertPendingOutbox();
        try {
            assertThat(attemptService.claim(
                    outbox.id(), "attempt-a", 300)).isTrue();
            LocalDateTime beforeFailure = currentDatabaseTime();
            assertThat(attemptService.markFailed(
                    outbox.id(),
                    "attempt-a",
                    "broker unavailable",
                    30
            )).isTrue();
            LocalDateTime afterFailure = currentDatabaseTime();

            assertOwner(outbox.id(), "FAILED", null, 1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT failure_reason FROM analysis_outbox_event WHERE id = ?",
                    String.class,
                    outbox.id()
            )).isEqualTo("broker unavailable");
            LocalDateTime nextRetryAt = jdbcTemplate.queryForObject(
                    "SELECT next_retry_at FROM analysis_outbox_event WHERE id = ?",
                    LocalDateTime.class,
                    outbox.id()
            );
            assertThat(nextRetryAt).isBetween(
                    beforeFailure.plusSeconds(30),
                    afterFailure.plusSeconds(30)
            );
        } finally {
            deleteOutbox(outbox);
        }
    }

    @Test
    void stalePublishingAttemptCanBeRecoveredAndSentAgain() {
        TestOutbox outbox = insertPendingOutbox();
        try {
            assertThat(attemptService.claim(
                    outbox.id(), "attempt-a", 0)).isTrue();

            assertThat(attemptService.claim(
                    outbox.id(), "attempt-b", 300)).isTrue();
            assertThat(attemptService.markSent(
                    outbox.id(),
                    "attempt-b",
                    "message-b"
            )).isTrue();
            assertOwner(outbox.id(), "SENT", null, 0);
        } finally {
            deleteOutbox(outbox);
        }
    }

    @Test
    void concurrentPublishersCannotOwnSameAttempt() throws Exception {
        TestOutbox outbox = insertPendingOutbox();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService publishers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> claims = new ArrayList<>();
            for (String token : List.of("attempt-a", "attempt-b")) {
                claims.add(publishers.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return attemptService.claim(outbox.id(), token, 300);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long owners = 0;
            for (Future<Boolean> claim : claims) {
                if (claim.get(5, TimeUnit.SECONDS)) {
                    owners++;
                }
            }
            assertThat(owners).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT publish_token FROM analysis_outbox_event WHERE id = ?",
                    String.class,
                    outbox.id()
            )).isIn("attempt-a", "attempt-b");
        } finally {
            start.countDown();
            publishers.shutdownNow();
            deleteOutbox(outbox);
        }
    }

    @Test
    void brokerAcceptedButUnmarkedAttemptMayBeSentAgainWithoutLoss() {
        TestOutbox outbox = insertPendingOutbox();
        try {
            assertThat(attemptService.claim(
                    outbox.id(), "attempt-a", 0)).isTrue();
            // Simulate broker acceptance followed by a process crash before markSent.
            assertOwner(outbox.id(), "PUBLISHING", "attempt-a", 0);

            assertThat(attemptService.claim(
                    outbox.id(), "attempt-b", 300)).isTrue();
            assertThat(attemptService.markSent(
                    outbox.id(),
                    "attempt-b",
                    "duplicate-allowed"
            )).isTrue();
            assertOwner(outbox.id(), "SENT", null, 0);
        } finally {
            deleteOutbox(outbox);
        }
    }

    @Test
    void shutdownUnfinishedAttemptCanBeRecoveredAndFencesLateSuccess()
            throws Exception {
        TestOutbox outbox = insertPendingOutbox();
        CountDownLatch sendStarted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        AnalysisTaskMessageProducer messageProducer =
                mock(AnalysisTaskMessageProducer.class);
        CourseInsightMetrics metrics = mock(CourseInsightMetrics.class);
        executeMetricSupplier(metrics);
        given(messageProducer.send(any(AnalysisTaskCreatedEvent.class)))
                .willAnswer(invocation -> {
                    sendStarted.countDown();
                    awaitIgnoringInterrupts(releaseSend);
                    return "message-a";
                });
        AnalysisOutboxPublisher publisher = new AnalysisOutboxPublisher(
                attemptService,
                messageProducer,
                1,
                1,
                30,
                0,
                0,
                metrics
        );
        ExecutorService scheduler = Executors.newSingleThreadExecutor();
        try {
            Future<?> publishCycle = scheduler.submit(publisher::publishPending);
            assertThat(sendStarted.await(5, TimeUnit.SECONDS)).isTrue();

            OwnerState firstAttempt = ownerState(outbox.id());
            assertThat(firstAttempt.status()).isEqualTo("PUBLISHING");
            assertThat(firstAttempt.publishToken()).hasSize(32);

            shutdownPublisher(publisher);
            assertOwner(
                    outbox.id(),
                    "PUBLISHING",
                    firstAttempt.publishToken(),
                    0
            );

            assertThat(attemptService.claim(
                    outbox.id(), "attempt-b", 300)).isTrue();
            assertOwner(outbox.id(), "PUBLISHING", "attempt-b", 0);

            releaseSend.countDown();
            publishCycle.get(5, TimeUnit.SECONDS);
            assertOwner(outbox.id(), "PUBLISHING", "attempt-b", 0);

            assertThat(attemptService.markSent(
                    outbox.id(), "attempt-b", "message-b")).isTrue();
            assertOwner(outbox.id(), "SENT", null, 0);
        } finally {
            releaseSend.countDown();
            shutdownPublisher(publisher);
            scheduler.shutdownNow();
            deleteOutbox(outbox);
        }
    }

    private TestOutbox insertPendingOutbox() {
        String eventId = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update(
                """
                INSERT INTO analysis_outbox_event
                    (event_id, task_id, comment_id, event_type, status,
                     retry_count, next_retry_at)
                VALUES (?, 1, 1, 'COMMENT_ANALYSIS_CREATED', 'PENDING', 0,
                        CURRENT_TIMESTAMP(3))
                """,
                eventId
        );
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM analysis_outbox_event WHERE event_id = ?",
                Long.class,
                eventId
        );
        return new TestOutbox(id, eventId);
    }

    private void assertOwner(
            Long outboxId,
            String expectedStatus,
            String expectedToken,
            int expectedRetryCount) {
        OwnerState state = ownerState(outboxId);
        assertThat(state).isEqualTo(new OwnerState(
                expectedStatus,
                expectedToken,
                expectedRetryCount
        ));
    }

    private OwnerState ownerState(Long outboxId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT status, publish_token, retry_count
                FROM analysis_outbox_event
                WHERE id = ?
                """,
                (resultSet, rowNumber) -> new OwnerState(
                        resultSet.getString("status"),
                        resultSet.getString("publish_token"),
                        resultSet.getInt("retry_count")
                ),
                outboxId
        );
    }

    private LocalDateTime currentDatabaseTime() {
        return jdbcTemplate.queryForObject(
                "SELECT CURRENT_TIMESTAMP(3)",
                LocalDateTime.class
        );
    }

    @SuppressWarnings("unchecked")
    private void executeMetricSupplier(CourseInsightMetrics metrics) {
        given(metrics.recordOutboxSend(any(Supplier.class)))
                .willAnswer(invocation -> {
                    Supplier<?> supplier = invocation.getArgument(0);
                    return supplier.get();
                });
    }

    private void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void shutdownPublisher(AnalysisOutboxPublisher publisher) {
        ReflectionTestUtils.invokeMethod(publisher, "shutdown");
    }

    private void deleteOutbox(TestOutbox outbox) {
        jdbcTemplate.update(
                "DELETE FROM analysis_outbox_event WHERE event_id = ?",
                outbox.eventId()
        );
    }

    private record TestOutbox(Long id, String eventId) {
    }

    private record OwnerState(String status, String publishToken, int retryCount) {
    }
}
