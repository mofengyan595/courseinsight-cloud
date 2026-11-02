package com.courseinsight.server;

import com.courseinsight.server.cache.CourseCacheLookup;
import com.courseinsight.server.cache.CourseDetailCache;
import com.courseinsight.server.dto.AnalysisBatchCommentRow;
import com.courseinsight.server.dto.CommentCreateRequest;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.CourseNotEmptyException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.service.AnalysisBatchCreationService;
import com.courseinsight.server.service.CommentService;
import com.courseinsight.server.service.CourseService;
import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MySqlIntegrationTest
@AutoConfigureMockMvc
class CourseDeletionIntegrationTests {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CourseService service;
    @Autowired private CommentService commentService;
    @Autowired private AnalysisBatchCreationService batchService;
    @Autowired private CourseDetailCache cache;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<Long> createdCourses = new ArrayList<>();

    @AfterEach
    void cleanOnlyTestData() {
        for (Long id : createdCourses) {
            jdbc.update("DELETE FROM analysis_outbox_event WHERE comment_id IN (SELECT id FROM course_comment WHERE course_id = ?)", id);
            jdbc.update("DELETE FROM analysis_result WHERE course_id = ?", id);
            jdbc.update("DELETE FROM analysis_task WHERE course_id = ?", id);
            jdbc.update("DELETE FROM course_comment WHERE course_id = ?", id);
            jdbc.update("DELETE FROM analysis_batch WHERE course_id = ?", id);
            jdbc.update("DELETE FROM course WHERE id = ?", id);
        }
    }

    @Test
    void shouldCreateQueryDeleteAndReturnNotFoundOnRetest() throws Exception {
        given(cache.get(anyLong())).willReturn(CourseCacheLookup.miss());
        String code = randomId();
        var created = mvc.perform(post("/api/courses").with(user("11").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "code", code, "name", "复测课程", "teacherName", "测试教师"))))
                .andExpect(status().isCreated()).andReturn();
        Long id = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").longValue();
        createdCourses.add(id);
        mvc.perform(get("/api/courses/{id}", id).with(user("11").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value(code));
        mvc.perform(delete("/api/courses/{id}", id).with(user("11").roles("ADMIN")))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(get("/api/courses/{id}", id).with(user("11").roles("ADMIN")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/courses/{id}", id).with(user("11").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void shouldRejectCoursesWithHiddenOrActiveComments(int status) throws Exception {
        Long id = course();
        jdbc.update("INSERT INTO course_comment (course_id, content, rating, status) VALUES (?, '评价', 5, ?)", id, status);
        assertConflict(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_comment WHERE course_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void shouldRejectCourseWithBatchEvenWithoutComments() throws Exception {
        Long id = course();
        jdbc.update("INSERT INTO analysis_batch (batch_no, course_id, created_by, original_filename, total_count) VALUES (?, ?, 11, 'test.csv', 1)", randomId(), id);
        assertConflict(id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_batch WHERE course_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void shouldRejectCourseWithResidualTask() throws Exception {
        Long id = course();
        Long commentId = comment(id);
        jdbc.update("INSERT INTO analysis_task (task_no, course_id, comment_id, status) VALUES (?, ?, ?, 'WAITING')", randomId(), id, commentId);
        jdbc.update("DELETE FROM course_comment WHERE id = ?", commentId);
        assertConflict(id);
    }

    @Test
    void shouldRejectCourseWithResidualResult() throws Exception {
        Long id = course();
        Long commentId = comment(id);
        String taskNo = randomId();
        jdbc.update("INSERT INTO analysis_task (task_no, course_id, comment_id, status) VALUES (?, ?, ?, 'SUCCESS')", taskNo, id, commentId);
        Long taskId = jdbc.queryForObject("SELECT id FROM analysis_task WHERE task_no = ?", Long.class, taskNo);
        jdbc.update("""
                INSERT INTO analysis_result (task_id, comment_id, course_id, language, sentiment,
                    confidence, sentiment_source, sentiment_device, topics_json, topic_evidence_json,
                    keywords_json, long_text_handled, long_text_truncated)
                VALUES (?, ?, ?, 'zh', 'neutral', 0.5, 'rule', 'cpu', '[]', '[]', '[]', 0, 0)
                """, taskId, commentId, id);
        jdbc.update("DELETE FROM analysis_task WHERE id = ?", taskId);
        jdbc.update("DELETE FROM course_comment WHERE id = ?", commentId);
        assertConflict(id);
    }

    @Test
    void shouldEvictDeletedCourseOnlyAfterCommitAndPreserveCacheOnRollback() {
        Long committed = course();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            service.delete(11L, UserRole.ADMIN, committed);
            verify(cache, never()).evict(committed);
        });
        verify(cache).evict(committed);
        Long rolledBack = course();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            service.delete(11L, UserRole.ADMIN, rolledBack);
            tx.setRollbackOnly();
        });
        verify(cache, never()).evict(rolledBack);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course WHERE id = ?", Integer.class, rolledBack)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Writer.class)
    void shouldRejectDeletionAfterConcurrentAssociationCommits(Writer writer) throws Exception {
        Long id = course();
        var executor = Executors.newSingleThreadExecutor();
        var started = new CountDownLatch(1);
        try {
            var deletion = new TransactionTemplate(transactionManager).execute(tx -> {
                createAssociation(writer, id);
                var pending = executor.submit(() -> {
                    started.countDown();
                    assertThatThrownBy(() -> service.delete(11L, UserRole.ADMIN, id))
                            .isInstanceOf(CourseNotEmptyException.class);
                });
                await(started);
                assertThatThrownBy(() -> pending.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                return pending;
            });
            deletion.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course WHERE id = ?", Integer.class, id)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(Writer.class)
    void shouldRejectConcurrentAssociationAfterDeletionCommits(Writer writer) throws Exception {
        Long id = course();
        var executor = Executors.newSingleThreadExecutor();
        var started = new CountDownLatch(1);
        try {
            var creation = new TransactionTemplate(transactionManager).execute(tx -> {
                service.delete(11L, UserRole.ADMIN, id);
                var pending = executor.submit(() -> {
                    started.countDown();
                    assertThatThrownBy(() -> createAssociation(writer, id)).isInstanceOf(ResourceNotFoundException.class);
                });
                await(started);
                assertThatThrownBy(() -> pending.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                return pending;
            });
            creation.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course_comment WHERE course_id = ?", Integer.class, id)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_batch WHERE course_id = ?", Integer.class, id)).isZero();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertConflict(Long id) throws Exception {
        mvc.perform(delete("/api/courses/{id}", id).with(user("11").roles("ADMIN")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM course WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    private Long course() {
        String code = randomId();
        jdbc.update("INSERT INTO course (code, name, teacher_name, owner_user_id) VALUES (?, '课程', '教师', 11)", code);
        Long id = jdbc.queryForObject("SELECT id FROM course WHERE code = ?", Long.class, code);
        createdCourses.add(id);
        return id;
    }

    private Long comment(Long courseId) {
        jdbc.update("INSERT INTO course_comment (course_id, content, rating) VALUES (?, '评价', 5)", courseId);
        return jdbc.queryForObject("SELECT id FROM course_comment WHERE course_id = ?", Long.class, courseId);
    }

    private void createAssociation(Writer writer, Long id) {
        if (writer == Writer.COMMENT) {
            commentService.create(id, 11L, UserRole.ADMIN, new CommentCreateRequest("评价", 5));
        } else {
            batchService.create(id, 11L, "test.csv", List.of(new AnalysisBatchCommentRow(2, "评价", 5)));
        }
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private enum Writer { COMMENT, BATCH }
}
