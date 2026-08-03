package com.courseinsight.server;

import com.courseinsight.server.dto.CommentCreateRequest;
import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import com.courseinsight.server.service.CommentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@SpringBootTest
class CommentOutboxTransactionTests {

    @Autowired
    private CommentService commentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AnalysisOutboxEventMapper outboxEventMapper;

    @Test
    void shouldRollbackCommentAndTaskWhenOutboxCreationFails() {
        String courseCode = "OX" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update(
                """
                INSERT INTO course (code, name, teacher_name, description, status)
                VALUES (?, ?, ?, ?, 1)
                """,
                courseCode,
                "Outbox 事务测试课程",
                "测试教师",
                "验证 Outbox 写入失败时整个业务回滚"
        );
        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM course WHERE code = ?",
                Long.class,
                courseCode
        );

        try {
            given(outboxEventMapper.insert(any(AnalysisOutboxEvent.class)))
                    .willThrow(new DataIntegrityViolationException("模拟 Outbox 写入失败"));

            assertThatThrownBy(() -> commentService.create(
                    courseId,
                    700002L,
                    new CommentCreateRequest("Outbox 事务回滚测试", 4)
            )).isInstanceOf(DataIntegrityViolationException.class);

            Integer commentCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM course_comment WHERE course_id = ?",
                    Integer.class,
                    courseId
            );
            Integer taskCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM analysis_task WHERE course_id = ?",
                    Integer.class,
                    courseId
            );
            assertThat(commentCount).isZero();
            assertThat(taskCount).isZero();
        } finally {
            jdbcTemplate.update("DELETE FROM analysis_outbox_event WHERE task_id IN "
                    + "(SELECT id FROM analysis_task WHERE course_id = ?)", courseId);
            jdbcTemplate.update("DELETE FROM analysis_task WHERE course_id = ?", courseId);
            jdbcTemplate.update("DELETE FROM course_comment WHERE course_id = ?", courseId);
            jdbcTemplate.update("DELETE FROM course WHERE id = ?", courseId);
        }
    }
}
