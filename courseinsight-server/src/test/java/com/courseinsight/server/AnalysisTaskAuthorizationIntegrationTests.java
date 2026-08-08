package com.courseinsight.server;

import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.service.AnalysisTaskService;
import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@MySqlIntegrationTest
@Transactional
class AnalysisTaskAuthorizationIntegrationTests {

    private static final long STUDENT_A = 101L;
    private static final long STUDENT_B = 102L;
    private static final long TEACHER_A = 201L;
    private static final long TEACHER_B = 202L;

    @Autowired
    private AnalysisTaskService taskService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long courseA;
    private Long courseB;
    private Long commentA;
    private Long commentB;
    private Long batchComment;

    @BeforeEach
    void setUpOwnershipData() {
        courseA = insertCourse("A", TEACHER_A);
        courseB = insertCourse("B", TEACHER_B);
        commentA = insertComment(courseA, STUDENT_A, "student A comment");
        commentB = insertComment(courseB, STUDENT_B, "student B comment");
        batchComment = insertComment(courseA, null, "batch comment");
        insertTask(courseA, commentA);
        insertTask(courseB, commentB);
        insertTask(courseA, batchComment);
    }

    @Test
    void studentCannotReadAnotherStudentsTask() {
        assertThatThrownBy(() -> taskService.getByCommentId(
                commentB,
                STUDENT_A,
                UserRole.STUDENT
        )).isInstanceOf(CourseAccessDeniedException.class);
    }

    @Test
    void studentCanReadOwnTask() {
        assertThat(taskService.getByCommentId(
                commentA,
                STUDENT_A,
                UserRole.STUDENT
        ).commentId()).isEqualTo(commentA);
    }

    @Test
    void unrelatedTeacherCannotReadAnotherTeachersTask() {
        assertThatThrownBy(() -> taskService.getByCommentId(
                commentA,
                TEACHER_B,
                UserRole.TEACHER
        )).isInstanceOf(CourseAccessDeniedException.class);
    }

    @Test
    void owningTeacherCanReadTask() {
        assertThat(taskService.getByCommentId(
                commentA,
                TEACHER_A,
                UserRole.TEACHER
        ).commentId()).isEqualTo(commentA);
    }

    @Test
    void adminCanReadTask() {
        assertThat(taskService.getByCommentId(
                commentB,
                999L,
                UserRole.ADMIN
        ).commentId()).isEqualTo(commentB);
    }

    @Test
    void batchTaskWithoutStudentOwnerIsNotStudentReadable() {
        assertThatThrownBy(() -> taskService.getByCommentId(
                batchComment,
                STUDENT_A,
                UserRole.STUDENT
        )).isInstanceOf(CourseAccessDeniedException.class);
    }

    private Long insertCourse(String prefix, Long ownerId) {
        String code = prefix + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 20);
        jdbcTemplate.update(
                """
                INSERT INTO course
                    (code, name, teacher_name, owner_user_id, status)
                VALUES (?, 'Authorization course', 'Teacher', ?, 1)
                """,
                code,
                ownerId
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM course WHERE code = ?",
                Long.class,
                code
        );
    }

    private Long insertComment(Long courseId, Long userId, String content) {
        jdbcTemplate.update(
                """
                INSERT INTO course_comment
                    (course_id, user_id, content, rating, status)
                VALUES (?, ?, ?, 5, 1)
                """,
                courseId,
                userId,
                content
        );
        return jdbcTemplate.queryForObject(
                """
                SELECT id FROM course_comment
                WHERE course_id = ? AND content = ?
                """,
                Long.class,
                courseId,
                content
        );
    }

    private void insertTask(Long courseId, Long commentId) {
        jdbcTemplate.update(
                """
                INSERT INTO analysis_task
                    (task_no, comment_id, course_id, status, retry_count,
                     current_event_id)
                VALUES (?, ?, ?, 'WAITING', 0, ?)
                """,
                randomId(),
                commentId,
                courseId,
                randomId()
        );
    }

    private String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
