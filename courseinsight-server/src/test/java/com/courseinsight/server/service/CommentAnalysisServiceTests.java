package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.courseinsight.server.dto.CommentAnalysisResponse;
import com.courseinsight.server.entity.AnalysisResult;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.Course;
import com.courseinsight.server.entity.CourseComment;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisResultMapper;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.mapper.CourseCommentMapper;
import com.courseinsight.server.mapper.CourseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class CommentAnalysisServiceTests {
    @Mock private CourseCommentMapper comments;
    @Mock private AnalysisTaskMapper tasks;
    @Mock private AnalysisResultMapper results;
    @Mock private CourseMapper courses;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private CommentAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new CommentAnalysisService(comments, tasks, results,
                new CourseManagementAccessService(courses), json);
    }

    @Test
    void studentCannotReadEvenOwnAnalysisOrProbeExistence() {
        assertThatThrownBy(() -> service.getByCommentId(10L, 20L, UserRole.STUDENT))
                .isInstanceOf(CourseAccessDeniedException.class);
        verifyNoInteractions(comments, tasks, results, courses);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITING", "PROCESSING", "FAILED"})
    void unfinishedOrFailedTaskHasNullResult(String status) {
        prepare(status, 30L);
        CommentAnalysisResponse response = service.getByCommentId(10L, 30L, UserRole.TEACHER);
        assertThat(response.task().status()).isEqualTo(status);
        assertThat(response.task().retryCount()).isEqualTo(2);
        assertThat(response.task().failureReason()).isEqualTo("测试失败信息");
        assertThat(response.result()).isNull();
        verifyNoInteractions(results);
    }

    @Test
    void teacherReadsOwnCourseSuccessWithJsonAndNoStudentIdentity() {
        prepare("SUCCESS", 30L);
        prepareResult();
        CommentAnalysisResponse response = service.getByCommentId(10L, 30L, UserRole.TEACHER);
        assertThat(response.comment().content()).isEqualTo("课程讲解清晰");
        assertThat(response.comment().anonymous()).isTrue();
        assertThat(response.result().sentiment()).isEqualTo("neutral");
        assertThat(response.result().confidence()).isEqualByComparingTo("0.9");
        assertThat(response.result().topics().get(0).asText()).isEqualTo("clarity");
        assertThat(response.result().topicEvidence().get(0).get("evidence").asText()).isEqualTo("讲解清晰");
        assertThat(response.result().advice().get("summary").asText()).isEqualTo("增加实践");
        assertThat(response.result().sentimentSource()).isEqualTo("stub");
        String serialized = json.valueToTree(response).toString();
        assertThat(serialized).doesNotContain("userId", "username", "displayName", "executionToken", "currentEventId");
    }

    @Test
    void teacherCannotReadAnotherTeachersCourse() {
        prepareCommentAndCourse(31L);
        assertThatThrownBy(() -> service.getByCommentId(10L, 30L, UserRole.TEACHER))
                .isInstanceOf(CourseAccessDeniedException.class);
        verifyNoInteractions(tasks, results);
    }

    @Test
    void adminCanReadAnotherTeachersCourse() {
        prepare("SUCCESS", 31L);
        prepareResult();
        assertThat(service.getByCommentId(10L, 99L, UserRole.ADMIN).result()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 999999})
    void missingOrInvalidIdReturnsNotFound(long id) {
        assertThatThrownBy(() -> service.getByCommentId(id, 30L, UserRole.TEACHER))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(tasks, results);
    }

    @Test
    void deletedCommentCannotBeReadEvenByAdmin() {
        CourseComment comment = new CourseComment();
        comment.setStatus(0);
        given(comments.selectById(10L)).willReturn(comment);
        assertThatThrownBy(() -> service.getByCommentId(10L, 99L, UserRole.ADMIN))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(tasks, results, courses);
    }

    @Test
    void missingTaskReturnsNotFound() {
        prepareCommentAndCourse(30L);
        assertThatThrownBy(() -> service.getByCommentId(10L, 30L, UserRole.TEACHER))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void missingSuccessResultDoesNotInventAnalysis() {
        prepare("SUCCESS", 30L);
        assertThatThrownBy(() -> service.getByCommentId(10L, 30L, UserRole.TEACHER))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private void prepare(String status, Long owner) {
        prepareCommentAndCourse(owner);
        AnalysisTask task = new AnalysisTask();
        task.setId(3L);
        task.setCommentId(10L);
        task.setCourseId(1L);
        task.setStatus(status);
        task.setRetryCount(2);
        task.setFailureReason("测试失败信息");
        task.setCompletedAt(LocalDateTime.of(2026, 10, 11, 12, 0));
        given(tasks.selectOne(any(Wrapper.class))).willReturn(task);
    }

    private void prepareCommentAndCourse(Long owner) {
        CourseComment comment = new CourseComment();
        comment.setId(10L);
        comment.setCourseId(1L);
        comment.setUserId(20L);
        comment.setStatus(1);
        comment.setAnonymous(true);
        comment.setRating(5);
        comment.setContent("课程讲解清晰");
        given(comments.selectById(10L)).willReturn(comment);
        Course course = new Course();
        course.setId(1L);
        course.setOwnerUserId(owner);
        given(courses.selectById(1L)).willReturn(course);
    }

    private void prepareResult() {
        AnalysisResult result = new AnalysisResult();
        result.setId(8L);
        result.setSentiment("neutral");
        result.setConfidence(new BigDecimal("0.9"));
        result.setSentimentSource("stub");
        result.setTopicsJson("[\"clarity\"]");
        result.setTopicEvidenceJson("[{\"evidence\":\"讲解清晰\"}]");
        result.setKeywordsJson("[\"课程\"]");
        result.setAdviceJson("{\"summary\":\"增加实践\"}");
        given(results.selectOne(any(Wrapper.class))).willReturn(result);
    }
}
