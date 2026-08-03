package com.courseinsight.server;

import com.courseinsight.server.dto.CommentCreateRequest;
import com.courseinsight.server.dto.CommentPageQuery;
import com.courseinsight.server.exception.DuplicateCommentException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.service.CommentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CommentOwnershipIntegrationTests {

    @Autowired
    private CommentService commentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldKeepDeletedHistoryAndAllowASeparateReplacementComment() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Long ownerId = insertUser("owner_" + suffix);
        Long otherUserId = insertUser("other_" + suffix);
        Long courseId = insertCourse("DEL" + suffix);
        Long commentId = commentService.create(
                courseId,
                ownerId,
                new CommentCreateRequest("第一次评价", 5)
        );
        Long replacementCommentId = null;

        try {
            assertThatThrownBy(() -> commentService.delete(commentId, otherUserId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("评价不存在");
            assertThat(commentStatus(commentId)).isEqualTo(1);
            assertThat(commentService.page(
                    courseId,
                    new CommentPageQuery(1, 10)
            ).total()).isEqualTo(1);

            commentService.delete(commentId, ownerId);

            assertThat(commentStatus(commentId)).isZero();
            assertThat(commentService.page(
                    courseId,
                    new CommentPageQuery(1, 10)
            ).total()).isZero();
            assertThat(commentService.pageByUser(
                    ownerId,
                    new CommentPageQuery(1, 10)
            ).total()).isZero();
            assertThatThrownBy(() -> commentService.delete(commentId, ownerId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("评价不存在");

            Long newCommentId = commentService.create(
                    courseId,
                    ownerId,
                    new CommentCreateRequest("删除后重新评价", 4)
            );
            replacementCommentId = newCommentId;

            assertThat(newCommentId).isNotEqualTo(commentId);
            assertThat(commentStatus(commentId)).isZero();
            assertThat(commentStatus(newCommentId)).isEqualTo(1);
            assertThat(commentService.page(
                    courseId,
                    new CommentPageQuery(1, 10)
            ).items())
                    .singleElement()
                    .satisfies(comment -> {
                        assertThat(comment.id()).isEqualTo(newCommentId);
                        assertThat(comment.content()).isEqualTo("删除后重新评价");
                        assertThat(comment.rating()).isEqualTo(4);
                    });
            assertThat(commentService.pageByUser(
                    ownerId,
                    new CommentPageQuery(1, 10)
            ).total()).isEqualTo(1);
            assertThat(taskCount(commentId)).isEqualTo(1);
            assertThat(taskCount(newCommentId)).isEqualTo(1);

            assertThatThrownBy(() -> commentService.create(
                    courseId,
                    ownerId,
                    new CommentCreateRequest("活动评价不能重复", 3)
            ))
                    .isInstanceOf(DuplicateCommentException.class)
                    .hasMessage("你已经评价过该课程");
        } finally {
            jdbcTemplate.update(
                    """
                    DELETE FROM analysis_outbox_event
                    WHERE task_id IN (
                        SELECT id FROM analysis_task WHERE comment_id IN (?, ?)
                    )
                    """,
                    commentId,
                    replacementCommentId == null ? -1L : replacementCommentId
            );
            jdbcTemplate.update(
                    "DELETE FROM analysis_result WHERE comment_id IN (?, ?)",
                    commentId,
                    replacementCommentId == null ? -1L : replacementCommentId
            );
            jdbcTemplate.update(
                    "DELETE FROM analysis_task WHERE comment_id IN (?, ?)",
                    commentId,
                    replacementCommentId == null ? -1L : replacementCommentId
            );
            jdbcTemplate.update(
                    "DELETE FROM course_comment WHERE id IN (?, ?)",
                    commentId,
                    replacementCommentId == null ? -1L : replacementCommentId
            );
            jdbcTemplate.update("DELETE FROM course WHERE id = ?", courseId);
            jdbcTemplate.update(
                    "DELETE FROM app_user WHERE id IN (?, ?)",
                    ownerId,
                    otherUserId
            );
        }
    }

    private Long insertUser(String username) {
        jdbcTemplate.update(
                """
                INSERT INTO app_user
                    (username, password_hash, display_name, role, status)
                VALUES (?, ?, ?, 'STUDENT', 1)
                """,
                username,
                "test-password-hash",
                username
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username = ?",
                Long.class,
                username
        );
    }

    private Long insertCourse(String code) {
        jdbcTemplate.update(
                """
                INSERT INTO course (code, name, teacher_name, description, status)
                VALUES (?, '删除权限测试课程', '测试教师', '验证评价所有权', 1)
                """,
                code
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM course WHERE code = ?",
                Long.class,
                code
        );
    }

    private Integer commentStatus(Long commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM course_comment WHERE id = ?",
                Integer.class,
                commentId
        );
    }

    private Integer taskCount(Long commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analysis_task WHERE comment_id = ?",
                Integer.class,
                commentId
        );
    }
}
