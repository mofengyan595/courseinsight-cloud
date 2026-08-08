package com.courseinsight.server;

import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.AnalysisBatchResultItemResponse;
import com.courseinsight.server.dto.AnalysisBatchResultPageQuery;
import com.courseinsight.server.dto.AnalysisBatchRetryResponse;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.service.AnalysisBatchRecoveryService;
import com.courseinsight.server.service.AnalysisBatchResultService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "courseinsight.outbox.enabled=false")
@Transactional
class AnalysisBatchResultAndRecoveryIntegrationTests {

    @Autowired
    private AnalysisBatchResultService resultService;

    @Autowired
    private AnalysisBatchRecoveryService recoveryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPageSuccessfulAndFailedItemsInOneBatch() {
        TestBatch batch = createBatchWithTasks();
        insertResult(batch.successTaskId(), batch.successCommentId(), batch.courseId());

        PageResponse<AnalysisBatchResultItemResponse> response = resultService.page(
                batch.batchId(),
                999L,
                UserRole.ADMIN,
                new AnalysisBatchResultPageQuery(1, 20)
        );

        assertThat(response.total()).isEqualTo(2);
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).taskStatus()).isEqualTo("SUCCESS");
        assertThat(response.items().get(0).sentiment()).isEqualTo("positive");
        assertThat(response.items().get(0).topics().get(0).asText())
                .isEqualTo("examples");
        assertThat(response.items().get(1).taskStatus()).isEqualTo("FAILED");
        assertThat(response.items().get(1).resultId()).isNull();
        assertThat(response.items().get(1).deadLetteredAt()).isNotNull();
    }

    @Test
    void shouldReplayDeadLetteredTaskOnceWithoutCreatingNewCommentOrTask() {
        TestBatch batch = createBatchWithTasks();
        insertSentOutboxEvent(batch.failedTaskId(), batch.failedCommentId());

        AnalysisBatchRetryResponse first = recoveryService.retryDeadLettered(
                batch.batchId(),
                999L,
                UserRole.ADMIN
        );
        AnalysisBatchRetryResponse second = recoveryService.retryDeadLettered(
                batch.batchId(),
                999L,
                UserRole.ADMIN
        );

        assertThat(first.requeuedCount()).isEqualTo(1);
        assertThat(second.requeuedCount()).isZero();
        assertThat(queryString(
                "SELECT status FROM analysis_task WHERE id = ?",
                batch.failedTaskId()
        )).isEqualTo("WAITING");
        assertThat(queryInteger(
                "SELECT COUNT(*) FROM analysis_task WHERE batch_id = ?",
                batch.batchId()
        )).isEqualTo(2);
        assertThat(queryInteger(
                "SELECT COUNT(*) FROM course_comment WHERE course_id = ?",
                batch.courseId()
        )).isEqualTo(2);
        assertThat(queryInteger(
                "SELECT COUNT(*) FROM analysis_outbox_event WHERE task_id = ?",
                batch.failedTaskId()
        )).isEqualTo(2);
        assertThat(queryString(
                """
                SELECT status FROM analysis_outbox_event
                WHERE task_id = ? ORDER BY id DESC LIMIT 1
                """,
                batch.failedTaskId()
        )).isEqualTo("PENDING");
        assertThat(queryInteger(
                """
                SELECT COUNT(*) FROM analysis_task
                WHERE id = ? AND dead_lettered_at IS NULL
                """,
                batch.failedTaskId()
        )).isEqualTo(1);
    }

    private TestBatch createBatchWithTasks() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update(
                """
                INSERT INTO course
                    (code, name, teacher_name, status)
                VALUES (?, 'Batch integration course', 'Teacher', 1)
                """,
                "B" + suffix.substring(0, 20)
        );
        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM course WHERE code = ?",
                Long.class,
                "B" + suffix.substring(0, 20)
        );
        String batchNo = suffix.substring(0, 32);
        jdbcTemplate.update(
                """
                INSERT INTO analysis_batch
                    (batch_no, course_id, created_by, original_filename, total_count)
                VALUES (?, ?, 999, 'integration.csv', 2)
                """,
                batchNo,
                courseId
        );
        Long batchId = jdbcTemplate.queryForObject(
                "SELECT id FROM analysis_batch WHERE batch_no = ?",
                Long.class,
                batchNo
        );

        Long successCommentId = insertComment(courseId, "Clear examples", 5);
        Long failedCommentId = insertComment(courseId, "Service was unavailable", 2);
        Long successTaskId = insertTask(
                batchId, courseId, successCommentId, "SUCCESS", false);
        Long failedTaskId = insertTask(
                batchId, courseId, failedCommentId, "FAILED", true);
        return new TestBatch(
                courseId,
                batchId,
                successCommentId,
                failedCommentId,
                successTaskId,
                failedTaskId
        );
    }

    private Long insertComment(Long courseId, String content, int rating) {
        jdbcTemplate.update(
                """
                INSERT INTO course_comment
                    (course_id, user_id, content, rating, is_anonymous, status)
                VALUES (?, NULL, ?, ?, 1, 1)
                """,
                courseId,
                content,
                rating
        );
        return jdbcTemplate.queryForObject(
                """
                SELECT id FROM course_comment
                WHERE course_id = ? ORDER BY id DESC LIMIT 1
                """,
                Long.class,
                courseId
        );
    }

    private Long insertTask(
            Long batchId,
            Long courseId,
            Long commentId,
            String status,
            boolean deadLettered) {
        String taskNo = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update(
                """
                INSERT INTO analysis_task
                    (task_no, comment_id, course_id, batch_id, status,
                     retry_count, failure_reason, completed_at, dead_lettered_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, CURRENT_TIMESTAMP(3),
                        CASE WHEN ? THEN CURRENT_TIMESTAMP(3) ELSE NULL END)
                """,
                taskNo,
                commentId,
                courseId,
                batchId,
                status,
                deadLettered ? "AI service unavailable" : null,
                deadLettered
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM analysis_task WHERE task_no = ?",
                Long.class,
                taskNo
        );
    }

    private void insertResult(Long taskId, Long commentId, Long courseId) {
        jdbcTemplate.update(
                """
                INSERT INTO analysis_result
                    (task_id, comment_id, course_id, language, sentiment,
                     confidence, sentiment_source, sentiment_device,
                     topics_json, topic_evidence_json, keywords_json,
                     long_text_handled, long_text_truncated, advice_json,
                     risk_level, advice_source)
                VALUES (?, ?, ?, 'en', 'positive', 0.95000, 'bert', 'cpu',
                        JSON_ARRAY('examples'), JSON_ARRAY(), JSON_ARRAY('clear'),
                        0, 0, JSON_OBJECT('summary', 'Good course'),
                        'low', 'llm_api')
                """,
                taskId,
                commentId,
                courseId
        );
    }

    private void insertSentOutboxEvent(Long taskId, Long commentId) {
        jdbcTemplate.update(
                """
                INSERT INTO analysis_outbox_event
                    (event_id, task_id, comment_id, event_type, status,
                     retry_count, next_retry_at, sent_at)
                VALUES (?, ?, ?, 'COMMENT_ANALYSIS_CREATED', 'SENT', 0,
                        CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """,
                UUID.randomUUID().toString().replace("-", ""),
                taskId,
                commentId
        );
    }

    private Integer queryInteger(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, Integer.class, argument);
    }

    private String queryString(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, String.class, argument);
    }

    private record TestBatch(
            Long courseId,
            Long batchId,
            Long successCommentId,
            Long failedCommentId,
            Long successTaskId,
            Long failedTaskId) {
    }
}
