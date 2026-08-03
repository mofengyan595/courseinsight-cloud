package com.courseinsight.server;

import com.courseinsight.server.dto.CourseAnalyticsAggregate;
import com.courseinsight.server.mapper.CourseAnalyticsMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CourseAnalyticsMapperIntegrationTests {

    @Autowired
    private CourseAnalyticsMapper courseAnalyticsMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldAggregateOnlyActiveComments() {
        Long courseId = insertCourse();
        Long positiveCommentId = insertComment(courseId, "positive", 5, 1);
        Long neutralCommentId = insertComment(courseId, "neutral", 4, 1);
        Long negativeCommentId = insertComment(courseId, "negative", 3, 1);
        Long waitingCommentId = insertComment(courseId, "waiting", 2, 1);
        Long processingCommentId = insertComment(courseId, "processing", 1, 1);
        Long failedCommentId = insertComment(courseId, "failed", 3, 1);
        Long deletedCommentId = insertComment(courseId, "deleted", 5, 0);

        Long positiveTaskId = insertTask(courseId, positiveCommentId, "SUCCESS");
        Long neutralTaskId = insertTask(courseId, neutralCommentId, "SUCCESS");
        Long negativeTaskId = insertTask(courseId, negativeCommentId, "SUCCESS");
        insertTask(courseId, waitingCommentId, "WAITING");
        insertTask(courseId, processingCommentId, "PROCESSING");
        insertTask(courseId, failedCommentId, "FAILED");
        Long deletedTaskId = insertTask(courseId, deletedCommentId, "SUCCESS");

        insertResult(courseId, positiveCommentId, positiveTaskId, "positive", "high");
        insertResult(courseId, neutralCommentId, neutralTaskId, "neutral", "middle");
        insertResult(courseId, negativeCommentId, negativeTaskId, "negative", null);
        insertResult(courseId, deletedCommentId, deletedTaskId, "positive", "low");

        CourseAnalyticsAggregate aggregate = courseAnalyticsMapper.selectSummary(courseId);

        assertThat(aggregate.getTotalComments()).isEqualTo(6);
        assertThat(aggregate.getAverageRating()).isEqualByComparingTo("3.00");
        assertThat(aggregate.getTotalTasks()).isEqualTo(6);
        assertThat(aggregate.getWaitingTasks()).isEqualTo(1);
        assertThat(aggregate.getProcessingTasks()).isEqualTo(1);
        assertThat(aggregate.getSuccessTasks()).isEqualTo(3);
        assertThat(aggregate.getFailedTasks()).isEqualTo(1);
        assertThat(aggregate.getAnalyzedResults()).isEqualTo(3);
        assertThat(aggregate.getPositiveResults()).isEqualTo(1);
        assertThat(aggregate.getNeutralResults()).isEqualTo(1);
        assertThat(aggregate.getNegativeResults()).isEqualTo(1);
        assertThat(aggregate.getHighRiskResults()).isEqualTo(1);
        assertThat(aggregate.getMiddleRiskResults()).isEqualTo(1);
        assertThat(aggregate.getLowRiskResults()).isZero();
    }

    private Long insertCourse() {
        String courseCode = "AN" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12);
        jdbcTemplate.update(
                """
                INSERT INTO course (code, name, teacher_name, description, status)
                VALUES (?, 'Analytics test', 'Test teacher', 'Analytics SQL test', 1)
                """,
                courseCode
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM course WHERE code = ?",
                Long.class,
                courseCode
        );
    }

    private Long insertComment(
            Long courseId,
            String suffix,
            int rating,
            int status) {
        String content = "analytics-" + suffix + "-" + UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO course_comment (course_id, content, rating, status)
                VALUES (?, ?, ?, ?)
                """,
                courseId,
                content,
                rating,
                status
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM course_comment WHERE course_id = ? AND content = ?",
                Long.class,
                courseId,
                content
        );
    }

    private Long insertTask(Long courseId, Long commentId, String status) {
        String taskNo = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update(
                """
                INSERT INTO analysis_task
                    (task_no, comment_id, course_id, status, retry_count)
                VALUES (?, ?, ?, ?, 0)
                """,
                taskNo,
                commentId,
                courseId,
                status
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM analysis_task WHERE task_no = ?",
                Long.class,
                taskNo
        );
    }

    private void insertResult(
            Long courseId,
            Long commentId,
            Long taskId,
            String sentiment,
            String riskLevel) {
        jdbcTemplate.update(
                """
                INSERT INTO analysis_result
                    (task_id, comment_id, course_id, language, sentiment, confidence,
                     sentiment_source, sentiment_device, topics_json, topic_evidence_json,
                     keywords_json, long_text_handled, long_text_truncated, advice_json,
                     risk_level, advice_source)
                VALUES (?, ?, ?, 'zh', ?, 0.90000, 'test', 'cpu',
                        JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY(), 0, 0,
                        JSON_OBJECT(), ?, 'test')
                """,
                taskId,
                commentId,
                courseId,
                sentiment,
                riskLevel
        );
    }
}
