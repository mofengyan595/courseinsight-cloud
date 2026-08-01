package com.courseinsight.server.service;

import com.courseinsight.server.dto.CommentCreateRequest;
import com.courseinsight.server.entity.Course;
import com.courseinsight.server.entity.CourseComment;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.CourseCommentMapper;
import com.courseinsight.server.mapper.CourseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

        verifyNoInteractions(courseCommentMapper);
    }
}
