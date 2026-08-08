package com.courseinsight.server;

import com.courseinsight.server.dto.AnalysisBatchCommentRow;
import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import com.courseinsight.server.service.AnalysisBatchCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@SpringBootTest
class AnalysisBatchTransactionTests {

    @Autowired
    private AnalysisBatchCreationService creationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AnalysisOutboxEventMapper outboxEventMapper;

    @Test
    void shouldRollbackWholeBatchWhenOutboxCreationFails() {
        Long courseId = insertCourse();
        try {
            given(outboxEventMapper.insert(any(AnalysisOutboxEvent.class)))
                    .willThrow(new DataIntegrityViolationException(
                            "simulate outbox failure"
                    ));

            assertThatThrownBy(() -> creationService.create(
                    courseId,
                    11L,
                    "comments.csv",
                    List.of(new AnalysisBatchCommentRow(2, "讲解清晰", 5))
            )).isInstanceOf(DataIntegrityViolationException.class);

            assertThat(count("analysis_batch", "course_id", courseId)).isZero();
            assertThat(count("course_comment", "course_id", courseId)).isZero();
            assertThat(count("analysis_task", "course_id", courseId)).isZero();
        } finally {
            jdbcTemplate.update("DELETE FROM analysis_task WHERE course_id = ?", courseId);
            jdbcTemplate.update("DELETE FROM course_comment WHERE course_id = ?", courseId);
            jdbcTemplate.update("DELETE FROM analysis_batch WHERE course_id = ?", courseId);
            jdbcTemplate.update("DELETE FROM course WHERE id = ?", courseId);
        }
    }

    private Long insertCourse() {
        String courseCode = "BR" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12);
        jdbcTemplate.update(
                """
                INSERT INTO course (code, name, teacher_name, owner_user_id, status)
                VALUES (?, 'Batch Rollback Test', 'Test Teacher', 11, 1)
                """,
                courseCode
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM course WHERE code = ?",
                Long.class,
                courseCode
        );
    }

    private Integer count(String table, String column, Long value) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class,
                value
        );
    }
}
