package com.courseinsight.server;

import com.courseinsight.server.dto.CommentCreateRequest;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.service.CommentService;
import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@MySqlIntegrationTest
class CommentAnalysisTransactionTests {

    @Autowired
    private CommentService commentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AnalysisTaskMapper analysisTaskMapper;

    @Test
    void shouldRollbackCommentWhenTaskCreationFails() {
        String courseCode = "TX" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update(
                """
                INSERT INTO course (code, name, teacher_name, description, status)
                VALUES (?, ?, ?, ?, 1)
                """,
                courseCode,
                "事务测试课程",
                "测试教师",
                "验证评价和分析任务属于同一事务"
        );
        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM course WHERE code = ?",
                Long.class,
                courseCode
        );

        try {
            given(analysisTaskMapper.insert(any(AnalysisTask.class)))
                    .willThrow(new DataIntegrityViolationException("模拟分析任务写入失败"));

            assertThatThrownBy(() -> commentService.create(
                    courseId,
                    700001L,
                    new CommentCreateRequest("事务回滚测试", 5)
            )).isInstanceOf(DataIntegrityViolationException.class);

            Integer commentCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM course_comment WHERE course_id = ?",
                    Integer.class,
                    courseId
            );
            assertThat(commentCount).isZero();
        } finally {
            jdbcTemplate.update("DELETE FROM course_comment WHERE course_id = ?", courseId);
            jdbcTemplate.update("DELETE FROM analysis_task WHERE course_id = ?", courseId);
            jdbcTemplate.update("DELETE FROM course WHERE id = ?", courseId);
        }
    }
}
