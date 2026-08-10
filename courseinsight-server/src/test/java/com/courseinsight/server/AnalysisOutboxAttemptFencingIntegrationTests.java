package com.courseinsight.server;

import com.courseinsight.server.message.AnalysisOutboxAttemptService;
import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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
        LocalDateTime firstClaimAt = LocalDateTime.now();
        try {
            assertThat(attemptService.claim(
                    outbox.id(), "attempt-a", firstClaimAt, 0)).isTrue();
            assertThat(attemptService.claim(
                    outbox.id(), "attempt-b", firstClaimAt.plusSeconds(1), 300)).isTrue();

            assertThat(attemptService.markSent(
                    outbox.id(),
                    "attempt-a",
                    "stale-message",
                    firstClaimAt.plusSeconds(2)
            )).isFalse();
            assertOwner(outbox.id(), "PUBLISHING", "attempt-b", 0);
            assertThat(attemptService.markSent(
                    outbox.id(),
                    "attempt-b",
                    "current-message",
                    firstClaimAt.plusSeconds(2)
            )).isTrue();
            assertOwner(outbox.id(), "SENT", null, 0);
        } finally {
            deleteOutbox(outbox);
        }
    }

    @Test
    void lateFailureCannotFailNewPublishAttempt() {
        TestOutbox outbox = insertPendingOutbox();
        LocalDateTime firstClaimAt = LocalDateTime.now();
        try {
            assertThat(attemptService.claim(
                    outbox.id(), "attempt-a", firstClaimAt, 0)).isTrue();
            assertThat(attemptService.claim(
                    outbox.id(), "attempt-b", firstClaimAt.plusSeconds(1), 300)).isTrue();

            assertThat(attemptService.markFailed(
                    outbox.id(),
                    "attempt-a",
                    "late failure",
                    firstClaimAt.plusSeconds(30)
            )).isFalse();
            assertOwner(outbox.id(), "PUBLISHING", "attempt-b", 0);
        } finally {
            deleteOutbox(outbox);
        }
    }

    @Test
    void failedCurrentAttemptSchedulesRetryAndIncrementsCount() {
        TestOutbox outbox = insertPendingOutbox();
        LocalDateTime claimedAt = LocalDateTime.now();
        try {
            assertThat(attemptService.claim(
                    outbox.id(), "attempt-a", claimedAt, 300)).isTrue();
            assertThat(attemptService.markFailed(
                    outbox.id(),
                    "attempt-a",
                    "broker unavailable",
                    claimedAt.plusSeconds(30)
            )).isTrue();

            assertOwner(outbox.id(), "FAILED", null, 1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT failure_reason FROM analysis_outbox_event WHERE id = ?",
                    String.class,
                    outbox.id()
            )).isEqualTo("broker unavailable");
        } finally {
            deleteOutbox(outbox);
        }
    }

    @Test
    void stalePublishingAttemptCanBeRecoveredAndSentAgain() {
        TestOutbox outbox = insertPendingOutbox();
        LocalDateTime firstClaimAt = LocalDateTime.now();
        try {
            assertThat(attemptService.claim(
                    outbox.id(), "attempt-a", firstClaimAt, 0)).isTrue();

            assertThat(attemptService.claim(
                    outbox.id(), "attempt-b", firstClaimAt.plusSeconds(1), 300)).isTrue();
            assertThat(attemptService.markSent(
                    outbox.id(),
                    "attempt-b",
                    "message-b",
                    firstClaimAt.plusSeconds(2)
            )).isTrue();
            assertOwner(outbox.id(), "SENT", null, 0);
        } finally {
            deleteOutbox(outbox);
        }
    }

    @Test
    void concurrentPublishersCannotOwnSameAttempt() throws Exception {
        TestOutbox outbox = insertPendingOutbox();
        LocalDateTime claimAt = LocalDateTime.now();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService publishers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> claims = new ArrayList<>();
            for (String token : List.of("attempt-a", "attempt-b")) {
                claims.add(publishers.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return attemptService.claim(outbox.id(), token, claimAt, 300);
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
        LocalDateTime firstClaimAt = LocalDateTime.now();
        try {
            assertThat(attemptService.claim(
                    outbox.id(), "attempt-a", firstClaimAt, 0)).isTrue();
            // Simulate broker acceptance followed by a process crash before markSent.
            assertOwner(outbox.id(), "PUBLISHING", "attempt-a", 0);

            assertThat(attemptService.claim(
                    outbox.id(), "attempt-b", firstClaimAt.plusSeconds(1), 300)).isTrue();
            assertThat(attemptService.markSent(
                    outbox.id(),
                    "attempt-b",
                    "duplicate-allowed",
                    firstClaimAt.plusSeconds(2)
            )).isTrue();
            assertOwner(outbox.id(), "SENT", null, 0);
        } finally {
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
        OwnerState state = jdbcTemplate.queryForObject(
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
        assertThat(state).isEqualTo(new OwnerState(
                expectedStatus,
                expectedToken,
                expectedRetryCount
        ));
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
