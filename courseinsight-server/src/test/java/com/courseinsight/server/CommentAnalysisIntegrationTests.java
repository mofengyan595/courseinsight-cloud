package com.courseinsight.server;

import com.courseinsight.server.client.AiAnalysisClient;
import com.courseinsight.server.client.AiAnalysisResponse;
import com.courseinsight.server.dto.CommentCreateRequest;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.service.AnalysisExecutionService;
import com.courseinsight.server.service.CommentAnalysisService;
import com.courseinsight.server.service.CommentService;
import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@MySqlIntegrationTest
class CommentAnalysisIntegrationTests {
    @Autowired private CommentService comments;
    @Autowired private CommentAnalysisService analysis;
    @Autowired private AnalysisExecutionService execution;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private AiAnalysisClient aiAnalysisClient;

    @Test
    void studentSubmissionCreatesTaskAndOutboxThenManagerReadsPersistedAnalysis() {
        String code = "CA" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbc.update("INSERT INTO course (code,name,teacher_name,owner_user_id,status) VALUES (?, '分析课程', '教师', 201, 1)", code);
        Long courseId = jdbc.queryForObject("SELECT id FROM course WHERE code=?", Long.class, code);
        try {
            Long commentId = comments.create(courseId, 101L, new CommentCreateRequest("课程讲解清晰", 5));
            Long taskId = jdbc.queryForObject("SELECT id FROM analysis_task WHERE comment_id=?", Long.class, commentId);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_outbox_event WHERE task_id=? AND status='PENDING'", Integer.class, taskId)).isEqualTo(1);
            assertThat(analysis.getByCommentId(commentId, 201L, UserRole.TEACHER).task().status()).isEqualTo("WAITING");
            assertThat(analysis.getByCommentId(commentId, 201L, UserRole.TEACHER).result()).isNull();
            assertThatThrownBy(() -> analysis.getByCommentId(commentId, 101L, UserRole.STUDENT))
                    .isInstanceOf(CourseAccessDeniedException.class);
            assertThatThrownBy(() -> analysis.getByCommentId(commentId, 202L, UserRole.TEACHER))
                    .isInstanceOf(CourseAccessDeniedException.class);

            // 数据库集成测试使用确定性 HTTP client fake；真实 RocketMQ + Stub 另由浏览器 Demo 验证。
            given(aiAnalysisClient.analyze(taskId, commentId, "课程讲解清晰", true))
                    .willReturn(new AiAnalysisResponse(taskId, commentId, "zh", "neutral",
                            new BigDecimal("0.9"), "stub", "cpu", List.of("clarity"),
                            List.of(new AiAnalysisResponse.TopicEvidence("clarity", List.of("讲解"), "讲解清晰")),
                            List.of("课程"), false, false,
                            new AiAnalysisResponse.ReviewAdvice("增加实践", List.of(), List.of(), "low", "stub", "zh", null)));
            execution.execute(taskId);

            var teacher = analysis.getByCommentId(commentId, 201L, UserRole.TEACHER);
            assertThat(teacher.task().status()).isEqualTo("SUCCESS");
            assertThat(teacher.task().completedAt()).isNotNull();
            assertThat(teacher.result().sentiment()).isEqualTo("neutral");
            assertThat(teacher.result().topicEvidence().get(0).get("evidence").asText()).isEqualTo("讲解清晰");
            assertThat(teacher.result().advice().get("summary").asText()).isEqualTo("增加实践");
            assertThat(analysis.getByCommentId(commentId, 999L, UserRole.ADMIN).result().id()).isEqualTo(teacher.result().id());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_result WHERE comment_id=?", Integer.class, commentId)).isEqualTo(1);

            comments.delete(commentId, 101L);
            assertThatThrownBy(() -> analysis.getByCommentId(commentId, 999L, UserRole.ADMIN))
                    .isInstanceOf(ResourceNotFoundException.class);
        } finally {
            jdbc.update("DELETE FROM analysis_result WHERE course_id=?", courseId);
            jdbc.update("DELETE FROM analysis_outbox_event WHERE task_id IN (SELECT id FROM analysis_task WHERE course_id=?)", courseId);
            jdbc.update("DELETE FROM analysis_task WHERE course_id=?", courseId);
            jdbc.update("DELETE FROM course_comment WHERE course_id=?", courseId);
            jdbc.update("DELETE FROM course WHERE id=?", courseId);
        }
    }
}
