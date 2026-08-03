package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.CommentCreateRequest;
import com.courseinsight.server.dto.CommentDetailResponse;
import com.courseinsight.server.dto.CommentPageQuery;
import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.entity.AnalysisOutboxStatus;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.AnalysisTaskStatus;
import com.courseinsight.server.entity.Course;
import com.courseinsight.server.entity.CourseComment;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.mapper.CourseCommentMapper;
import com.courseinsight.server.mapper.CourseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CommentServiceTests {

    @Mock
    private CourseMapper courseMapper;

    @Mock
    private CourseCommentMapper courseCommentMapper;

    @Mock
    private AnalysisTaskMapper analysisTaskMapper;

    @Mock
    private AnalysisOutboxEventMapper outboxEventMapper;

    @InjectMocks
    private CommentService commentService;

    @Test
    void shouldCreateCommentForExistingCourse() {
        Course course = new Course();
        course.setId(1L);
        given(courseMapper.selectById(1L)).willReturn(course);
        given(courseCommentMapper.insert(any(CourseComment.class)))
                .willAnswer(invocation -> {
                    CourseComment comment = invocation.getArgument(0);
                    comment.setId(10L);
                    return 1;
                });
        given(analysisTaskMapper.insert(any(AnalysisTask.class)))
                .willAnswer(invocation -> {
                    AnalysisTask task = invocation.getArgument(0);
                    task.setId(20L);
                    return 1;
                });
        given(outboxEventMapper.insert(any(AnalysisOutboxEvent.class))).willReturn(1);

        Long commentId = commentService.create(
                1L,
                new CommentCreateRequest("课程讲解清晰", 5)
        );

        assertThat(commentId).isEqualTo(10L);

        ArgumentCaptor<CourseComment> captor = ArgumentCaptor.forClass(CourseComment.class);
        verify(courseCommentMapper).insert(captor.capture());
        CourseComment savedComment = captor.getValue();
        assertThat(savedComment.getCourseId()).isEqualTo(1L);
        assertThat(savedComment.getContent()).isEqualTo("课程讲解清晰");
        assertThat(savedComment.getRating()).isEqualTo(5);
        assertThat(savedComment.getStatus()).isEqualTo(1);

        ArgumentCaptor<AnalysisTask> taskCaptor = ArgumentCaptor.forClass(AnalysisTask.class);
        verify(analysisTaskMapper).insert(taskCaptor.capture());
        AnalysisTask savedTask = taskCaptor.getValue();
        assertThat(savedTask.getTaskNo()).hasSize(32);
        assertThat(savedTask.getCommentId()).isEqualTo(10L);
        assertThat(savedTask.getCourseId()).isEqualTo(1L);
        assertThat(savedTask.getStatus()).isEqualTo(AnalysisTaskStatus.WAITING.name());
        assertThat(savedTask.getRetryCount()).isZero();

        ArgumentCaptor<AnalysisOutboxEvent> outboxCaptor =
                ArgumentCaptor.forClass(AnalysisOutboxEvent.class);
        verify(outboxEventMapper).insert(outboxCaptor.capture());
        AnalysisOutboxEvent savedEvent = outboxCaptor.getValue();
        assertThat(savedEvent.getEventId()).hasSize(32);
        assertThat(savedEvent.getTaskId()).isEqualTo(20L);
        assertThat(savedEvent.getCommentId()).isEqualTo(10L);
        assertThat(savedEvent.getStatus()).isEqualTo(AnalysisOutboxStatus.PENDING.name());
        assertThat(savedEvent.getRetryCount()).isZero();
    }

    @Test
    void shouldRejectCommentForMissingCourse() {
        given(courseMapper.selectById(999999L)).willReturn(null);

        assertThatThrownBy(() -> commentService.create(
                999999L,
                new CommentCreateRequest("课程评价", 4)
        ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("课程不存在");

        verifyNoInteractions(courseCommentMapper, analysisTaskMapper, outboxEventMapper);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldPageCommentsForExistingCourse() {
        Course course = new Course();
        course.setId(1L);
        given(courseMapper.selectById(1L)).willReturn(course);

        Page<CourseComment> mapperResult = new Page<>(1, 10, 1);
        mapperResult.setRecords(List.of(createComment()));
        given(courseCommentMapper.selectPage(any(Page.class), any(Wrapper.class)))
                .willReturn(mapperResult);

        PageResponse<CommentDetailResponse> response = commentService.page(
                1L,
                new CommentPageQuery(1, 10)
        );

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.total()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).id()).isEqualTo(10L);
        assertThat(response.items().get(0).courseId()).isEqualTo(1L);
        assertThat(response.items().get(0).content()).isEqualTo("课程讲解清晰");
    }

    @Test
    void shouldRejectPageForMissingCourse() {
        given(courseMapper.selectById(999999L)).willReturn(null);

        assertThatThrownBy(() -> commentService.page(
                999999L,
                new CommentPageQuery(1, 10)
        ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("课程不存在");

        verifyNoInteractions(courseCommentMapper);
    }

    private CourseComment createComment() {
        CourseComment comment = new CourseComment();
        comment.setId(10L);
        comment.setCourseId(1L);
        comment.setContent("课程讲解清晰");
        comment.setRating(5);
        comment.setStatus(1);
        comment.setCreatedAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        comment.setUpdatedAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        return comment;
    }
}
