package com.courseinsight.server;

import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import com.courseinsight.server.service.AnalysisBatchRecoveryService;
import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@MySqlIntegrationTest
class AnalysisBatchRecoveryTransactionTests {

    @Autowired
    private AnalysisBatchRecoveryService recoveryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AnalysisOutboxEventMapper outboxEventMapper;

    @Test
    void shouldRollbackTaskStateWhenReplayOutboxInsertFails() {
        TestData data = createDeadLetteredBatch();
        given(outboxEventMapper.insert(any(AnalysisOutboxEvent.class)))
                .willThrow(new IllegalStateException("simulate replay outbox failure"));

        try {
            assertThatThrownBy(() -> recoveryService.retryDeadLettered(
                    data.batchId(),
                    999L,
                    UserRole.ADMIN
            )).isInstanceOf(IllegalStateException.class);

            assertThat(queryString(
                    "SELECT status FROM analysis_task WHERE id = ?",
                    data.taskId()
            )).isEqualTo("FAILED");
            assertThat(queryInteger(
                    """
                    SELECT COUNT(*) FROM analysis_task
                    WHERE id = ? AND dead_lettered_at IS NOT NULL
                    """,
                    data.taskId()
            )).isEqualTo(1);
        } finally {
            deleteTestData(data);
        }
    }

    private TestData createDeadLetteredBatch() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String courseCode = "R" + suffix.substring(0, 20);
        jdbcTemplate.update(
                """
                INSERT INTO course (code, name, teacher_name, status)
                VALUES (?, 'Recovery rollback course', 'Teacher', 1)
                """,
                courseCode
        );
        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM course WHERE code = ?",
                Long.class,
                courseCode
        );
        String batchNo = suffix.substring(0, 32);
        jdbcTemplate.update(
                """
                INSERT INTO analysis_batch
                    (batch_no, course_id, created_by, original_filename, total_count)
                VALUES (?, ?, 999, 'rollback.csv', 1)
                """,
                batchNo,
                courseId
        );
        Long batchId = jdbcTemplate.queryForObject(
                "SELECT id FROM analysis_batch WHERE batch_no = ?",
                Long.class,
                batchNo
        );
        jdbcTemplate.update(
                """
                INSERT INTO course_comment
                    (course_id, user_id, content, rating, is_anonymous, status)
                VALUES (?, NULL, 'Retry me', 2, 1, 1)
                """,
                courseId
        );
        Long commentId = jdbcTemplate.queryForObject(
                """
                SELECT id FROM course_comment
                WHERE course_id = ? ORDER BY id DESC LIMIT 1
                """,
                Long.class,
                courseId
        );
        String taskNo = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update(
                """
                INSERT INTO analysis_task
                    (task_no, comment_id, course_id, batch_id, status,
                     retry_count, failure_reason, completed_at, dead_lettered_at)
                VALUES (?, ?, ?, ?, 'FAILED', 3, 'AI unavailable',
                        CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """,
                taskNo,
                commentId,
                courseId,
                batchId
        );
        Long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM analysis_task WHERE task_no = ?",
                Long.class,
                taskNo
        );
        return new TestData(courseId, batchId, commentId, taskId);
    }

    private void deleteTestData(TestData data) {
        jdbcTemplate.update("DELETE FROM analysis_outbox_event WHERE task_id = ?", data.taskId());
        jdbcTemplate.update("DELETE FROM analysis_result WHERE task_id = ?", data.taskId());
        jdbcTemplate.update("DELETE FROM analysis_task WHERE id = ?", data.taskId());
        jdbcTemplate.update("DELETE FROM course_comment WHERE id = ?", data.commentId());
        jdbcTemplate.update("DELETE FROM analysis_batch WHERE id = ?", data.batchId());
        jdbcTemplate.update("DELETE FROM course WHERE id = ?", data.courseId());
    }

    private Integer queryInteger(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, Integer.class, argument);
    }

    private String queryString(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, String.class, argument);
    }

    private record TestData(Long courseId, Long batchId, Long commentId, Long taskId) {
    }
}
