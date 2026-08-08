package com.courseinsight.server;

import com.courseinsight.server.client.AiAnalysisClient;
import com.courseinsight.server.client.AiAnalysisResponse;
import com.courseinsight.server.dto.AnalysisBatchProgressAggregate;
import com.courseinsight.server.exception.NonRetryableAiServiceException;
import com.courseinsight.server.exception.RetryableAiServiceException;
import com.courseinsight.server.mapper.AnalysisBatchProgressMapper;
import com.courseinsight.server.message.AnalysisTaskCreatedEvent;
import com.courseinsight.server.message.AnalysisTaskMessageConsumer;
import com.courseinsight.server.service.AnalysisExecutionService;
import com.courseinsight.server.service.AnalysisTaskDeadLetterService;
import com.courseinsight.server.service.AnalysisTaskLeaseRecoveryService;
import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@MySqlIntegrationTest
class AnalysisExecutionFencingIntegrationTests {

    @Autowired
    private AnalysisExecutionService executionService;

    @Autowired
    private AnalysisTaskLeaseRecoveryService leaseRecoveryService;

    @Autowired
    private AnalysisTaskDeadLetterService deadLetterService;

    @Autowired
    private AnalysisTaskMessageConsumer messageConsumer;

    @Autowired
    private AnalysisBatchProgressMapper batchProgressMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AiAnalysisClient aiAnalysisClient;

    @Test
    void staleWorkerCannotCompleteNewerGeneration() throws Exception {
        runStaleWorkerScenario(false);
    }

    @Test
    void staleWorkerCannotFailNewerGeneration() throws Exception {
        runStaleWorkerScenario(true);
    }

    @Test
    void staleNormalMessageCannotResurrectTerminalBatchTask() {
        TestTask task = createTask(true);
        try {
            LocalDateTime terminalAt = LocalDateTime.now().minusSeconds(1);
            jdbcTemplate.update(
                    """
                    UPDATE analysis_task
                    SET status = 'FAILED', completed_at = ?, dead_lettered_at = ?
                    WHERE id = ?
                    """,
                    terminalAt,
                    terminalAt,
                    task.taskId()
            );
            LocalDateTime persistedTerminalAt = jdbcTemplate.queryForObject(
                    "SELECT dead_lettered_at FROM analysis_task WHERE id = ?",
                    LocalDateTime.class,
                    task.taskId()
            );

            executionService.executeFromMessage(task.taskId(), task.eventId());

            assertThat(queryString(
                    "SELECT status FROM analysis_task WHERE id = ?",
                    task.taskId()
            )).isEqualTo("FAILED");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT dead_lettered_at FROM analysis_task WHERE id = ?",
                    LocalDateTime.class,
                    task.taskId()
            )).isEqualTo(persistedTerminalAt);
            AnalysisBatchProgressAggregate progress =
                    batchProgressMapper.selectProgress(task.batchId());
            assertThat(progress.getProcessingCount()).isZero();
            assertThat(progress.getFailedCount()).isEqualTo(1);
            verify(aiAnalysisClient, times(0)).analyze(
                    any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()
            );
        } finally {
            deleteTask(task);
        }
    }

    @Test
    void duplicateDlqCannotKillLiveUnexpiredOwner() {
        TestTask task = createTask(false);
        try {
            LocalDateTime leaseUntil = LocalDateTime.now().plusMinutes(2);
            jdbcTemplate.update(
                    """
                    UPDATE analysis_task
                    SET status = 'PROCESSING', execution_token = ?, lease_until = ?
                    WHERE id = ?
                    """,
                    randomId(),
                    leaseUntil,
                    task.taskId()
            );
            LocalDateTime persistedLeaseUntil = jdbcTemplate.queryForObject(
                    "SELECT lease_until FROM analysis_task WHERE id = ?",
                    LocalDateTime.class,
                    task.taskId()
            );

            assertThat(deadLetterService.markDeadLettered(
                    task.taskId(),
                    task.eventId()
            )).isFalse();
            assertThat(queryString(
                    "SELECT status FROM analysis_task WHERE id = ?",
                    task.taskId()
            )).isEqualTo("PROCESSING");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT lease_until FROM analysis_task WHERE id = ?",
                    LocalDateTime.class,
                    task.taskId()
            )).isEqualTo(persistedLeaseUntil);
        } finally {
            deleteTask(task);
        }
    }

    @Test
    void expiredProcessingTaskIsRecoveredThroughNewOutboxGeneration() {
        TestTask task = createTask(false);
        try {
            jdbcTemplate.update(
                    """
                    UPDATE analysis_task
                    SET status = 'PROCESSING', execution_token = ?,
                        lease_until = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
                    WHERE id = ?
                    """,
                    randomId(),
                    task.taskId()
            );

            assertThat(leaseRecoveryService.recoverExpired()).isEqualTo(1);
            String recoveredEventId = queryString(
                    "SELECT current_event_id FROM analysis_task WHERE id = ?",
                    task.taskId()
            );
            assertThat(recoveredEventId).isNotEqualTo(task.eventId());
            assertThat(queryString(
                    "SELECT status FROM analysis_task WHERE id = ?",
                    task.taskId()
            )).isEqualTo("WAITING");
            assertThat(jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM analysis_outbox_event
                    WHERE task_id = ? AND event_id = ? AND status = 'PENDING'
                    """,
                    Integer.class,
                    task.taskId(),
                    recoveredEventId
            )).isEqualTo(1);

            given(aiAnalysisClient.analyze(
                    task.taskId(),
                    task.commentId(),
                    "fencing test comment",
                    true
            )).willReturn(response(task, "positive"));
            executionService.executeFromMessage(task.taskId(), recoveredEventId);

            assertThat(queryString(
                    "SELECT status FROM analysis_task WHERE id = ?",
                    task.taskId()
            )).isEqualTo("SUCCESS");
        } finally {
            deleteTask(task);
        }
    }

    @Test
    void duplicateDeliveryOfSameOutboxEventCommitsOneEffectiveResult() {
        TestTask task = createTask(false);
        try {
            given(aiAnalysisClient.analyze(
                    task.taskId(),
                    task.commentId(),
                    "fencing test comment",
                    true
            )).willReturn(response(task, "positive"));

            executionService.executeFromMessage(task.taskId(), task.eventId());
            executionService.executeFromMessage(task.taskId(), task.eventId());

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM analysis_result WHERE task_id = ?",
                    Integer.class,
                    task.taskId()
            )).isEqualTo(1);
            verify(aiAnalysisClient, times(1)).analyze(
                    task.taskId(),
                    task.commentId(),
                    "fencing test comment",
                    true
            );
        } finally {
            deleteTask(task);
        }
    }

    @Test
    void retryableAiFailureRemainsEligibleForBrokerRetry() {
        TestTask task = createTask(false);
        try {
            given(aiAnalysisClient.analyze(
                    task.taskId(),
                    task.commentId(),
                    "fencing test comment",
                    true
            )).willThrow(new RetryableAiServiceException("temporary", null));

            assertThatThrownBy(() -> executionService.executeFromMessage(
                    task.taskId(),
                    task.eventId()
            )).isInstanceOf(RetryableAiServiceException.class);

            assertThat(queryString(
                    "SELECT status FROM analysis_task WHERE id = ?",
                    task.taskId()
            )).isEqualTo("FAILED");
            assertThat(jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM analysis_task
                    WHERE id = ? AND dead_lettered_at IS NULL
                    """,
                    Integer.class,
                    task.taskId()
            )).isEqualTo(1);
        } finally {
            deleteTask(task);
        }
    }

    @Test
    void nonRetryableAiFailureIsPersistedTerminalAndAcknowledged() {
        TestTask task = createTask(false);
        try {
            given(aiAnalysisClient.analyze(
                    task.taskId(),
                    task.commentId(),
                    "fencing test comment",
                    true
            )).willThrow(new NonRetryableAiServiceException("permanent"));

            messageConsumer.onMessage(new AnalysisTaskCreatedEvent(
                    task.eventId(),
                    task.taskId(),
                    task.commentId(),
                    AnalysisTaskCreatedEvent.EVENT_TYPE,
                    "2026-08-08T00:00:00Z"
            ));

            assertThat(queryString(
                    "SELECT status FROM analysis_task WHERE id = ?",
                    task.taskId()
            )).isEqualTo("FAILED");
            assertThat(jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM analysis_task
                    WHERE id = ? AND dead_lettered_at IS NOT NULL
                    """,
                    Integer.class,
                    task.taskId()
            )).isEqualTo(1);
        } finally {
            deleteTask(task);
        }
    }

    private void runStaleWorkerScenario(boolean staleWorkerFails) throws Exception {
        TestTask task = createTask(false);
        CountDownLatch staleEntered = new CountDownLatch(1);
        CountDownLatch releaseStale = new CountDownLatch(1);
        CountDownLatch currentEntered = new CountDownLatch(1);
        CountDownLatch releaseCurrent = new CountDownLatch(1);
        AtomicInteger callNumber = new AtomicInteger();
        given(aiAnalysisClient.analyze(
                task.taskId(),
                task.commentId(),
                "fencing test comment",
                true
        )).willAnswer(invocation -> {
            if (callNumber.incrementAndGet() == 1) {
                staleEntered.countDown();
                await(releaseStale);
                if (staleWorkerFails) {
                    throw new RetryableAiServiceException("temporary", null);
                }
                return response(task, "negative");
            }
            currentEntered.countDown();
            await(releaseCurrent);
            return response(task, "positive");
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> stale = executor.submit(() -> executionService.executeFromMessage(
                    task.taskId(),
                    task.eventId()
            ));
            assertThat(staleEntered.await(5, TimeUnit.SECONDS)).isTrue();
            jdbcTemplate.update(
                    """
                    UPDATE analysis_task
                    SET lease_until = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
                    WHERE id = ?
                    """,
                    task.taskId()
            );

            assertThat(leaseRecoveryService.recoverExpired()).isEqualTo(1);
            String newEventId = queryString(
                    "SELECT current_event_id FROM analysis_task WHERE id = ?",
                    task.taskId()
            );
            Future<?> current = executor.submit(() -> executionService.executeFromMessage(
                    task.taskId(),
                    newEventId
            ));
            assertThat(currentEntered.await(5, TimeUnit.SECONDS)).isTrue();

            releaseStale.countDown();
            stale.get(5, TimeUnit.SECONDS);

            assertThat(queryString(
                    "SELECT status FROM analysis_task WHERE id = ?",
                    task.taskId()
            )).isEqualTo("PROCESSING");
            assertThat(queryString(
                    "SELECT current_event_id FROM analysis_task WHERE id = ?",
                    task.taskId()
            )).isEqualTo(newEventId);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT execution_token FROM analysis_task WHERE id = ?",
                    String.class,
                    task.taskId()
            )).isNotBlank();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM analysis_result WHERE task_id = ?",
                    Integer.class,
                    task.taskId()
            )).isZero();

            releaseCurrent.countDown();
            current.get(5, TimeUnit.SECONDS);
            assertThat(queryString(
                    "SELECT status FROM analysis_task WHERE id = ?",
                    task.taskId()
            )).isEqualTo("SUCCESS");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM analysis_result WHERE task_id = ?",
                    Integer.class,
                    task.taskId()
            )).isEqualTo(1);
        } finally {
            releaseStale.countDown();
            releaseCurrent.countDown();
            executor.shutdownNow();
            deleteTask(task);
        }
    }

    private TestTask createTask(boolean batchTask) {
        String suffix = randomId();
        String courseCode = "F" + suffix.substring(0, 20);
        jdbcTemplate.update(
                """
                INSERT INTO course (code, name, teacher_name, status)
                VALUES (?, 'Fencing course', 'Teacher', 1)
                """,
                courseCode
        );
        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM course WHERE code = ?",
                Long.class,
                courseCode
        );
        jdbcTemplate.update(
                """
                INSERT INTO course_comment (course_id, user_id, content, rating, status)
                VALUES (?, 7001, 'fencing test comment', 5, 1)
                """,
                courseId
        );
        Long commentId = jdbcTemplate.queryForObject(
                "SELECT id FROM course_comment WHERE course_id = ? ORDER BY id DESC LIMIT 1",
                Long.class,
                courseId
        );
        Long batchId = null;
        if (batchTask) {
            jdbcTemplate.update(
                    """
                    INSERT INTO analysis_batch
                        (batch_no, course_id, created_by, original_filename, total_count)
                    VALUES (?, ?, 9001, 'fencing.csv', 1)
                    """,
                    suffix,
                    courseId
            );
            batchId = jdbcTemplate.queryForObject(
                    "SELECT id FROM analysis_batch WHERE batch_no = ?",
                    Long.class,
                    suffix
            );
        }
        String eventId = randomId();
        String taskNo = randomId();
        jdbcTemplate.update(
                """
                INSERT INTO analysis_task
                    (task_no, comment_id, course_id, batch_id, status,
                     retry_count, current_event_id)
                VALUES (?, ?, ?, ?, 'WAITING', 0, ?)
                """,
                taskNo,
                commentId,
                courseId,
                batchId,
                eventId
        );
        Long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM analysis_task WHERE task_no = ?",
                Long.class,
                taskNo
        );
        jdbcTemplate.update(
                """
                INSERT INTO analysis_outbox_event
                    (event_id, task_id, comment_id, event_type, status,
                     retry_count, next_retry_at, sent_at)
                VALUES (?, ?, ?, 'COMMENT_ANALYSIS_CREATED', 'SENT', 0,
                        CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """,
                eventId,
                taskId,
                commentId
        );
        return new TestTask(courseId, commentId, taskId, batchId, eventId);
    }

    private void deleteTask(TestTask task) {
        jdbcTemplate.update(
                "DELETE FROM analysis_outbox_event WHERE task_id = ?",
                task.taskId()
        );
        jdbcTemplate.update(
                "DELETE FROM analysis_result WHERE task_id = ?",
                task.taskId()
        );
        jdbcTemplate.update("DELETE FROM analysis_task WHERE id = ?", task.taskId());
        if (task.batchId() != null) {
            jdbcTemplate.update("DELETE FROM analysis_batch WHERE id = ?", task.batchId());
        }
        jdbcTemplate.update("DELETE FROM course_comment WHERE id = ?", task.commentId());
        jdbcTemplate.update("DELETE FROM course WHERE id = ?", task.courseId());
    }

    private AiAnalysisResponse response(TestTask task, String sentiment) {
        return new AiAnalysisResponse(
                task.taskId(),
                task.commentId(),
                "en",
                sentiment,
                new BigDecimal("0.90"),
                "bert",
                "cpu",
                List.of("clarity"),
                List.of(),
                List.of("clear"),
                false,
                false,
                null
        );
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test coordination");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private String queryString(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, String.class, argument);
    }

    private String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record TestTask(
            Long courseId,
            Long commentId,
            Long taskId,
            Long batchId,
            String eventId) {
    }
}
