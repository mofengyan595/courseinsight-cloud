package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.courseinsight.server.dto.AnalysisTaskDetailResponse;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.CourseComment;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.mapper.CourseCommentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class AnalysisTaskServiceTests {

    @Mock
    private AnalysisTaskMapper analysisTaskMapper;

    @Mock
    private CourseCommentMapper commentMapper;

    @Mock
    private CourseManagementAccessService managementAccessService;

    @InjectMocks
    private AnalysisTaskService analysisTaskService;

    @Test
    void shouldAllowStudentToReadOwnTask() {
        prepareTaskAndComment(20L);

        AnalysisTaskDetailResponse response = analysisTaskService.getByCommentId(
                10L,
                20L,
                UserRole.STUDENT
        );

        assertThat(response.id()).isEqualTo(3L);
    }

    @Test
    void shouldRejectStudentReadingAnotherStudentsTask() {
        prepareTaskAndComment(20L);

        assertThatThrownBy(() -> analysisTaskService.getByCommentId(
                10L,
                21L,
                UserRole.STUDENT
        )).isInstanceOf(CourseAccessDeniedException.class);
    }

    @Test
    void shouldRejectStudentReadingBatchTaskWithoutOwner() {
        prepareTaskAndComment(null);

        assertThatThrownBy(() -> analysisTaskService.getByCommentId(
                10L,
                20L,
                UserRole.STUDENT
        )).isInstanceOf(CourseAccessDeniedException.class);
    }

    @Test
    void shouldDelegateTeacherAndAdminCourseOwnershipChecks() {
        prepareTaskAndComment(20L);

        analysisTaskService.getByCommentId(10L, 30L, UserRole.TEACHER);
        analysisTaskService.getByCommentId(10L, 40L, UserRole.ADMIN);

        verify(managementAccessService).assertCanManage(1L, 30L, UserRole.TEACHER);
        verify(managementAccessService).assertCanManage(1L, 40L, UserRole.ADMIN);
    }

    @Test
    void shouldRejectUnrelatedTeacher() {
        prepareTaskAndComment(20L);
        willThrow(new CourseAccessDeniedException("denied"))
                .given(managementAccessService)
                .assertCanManage(1L, 31L, UserRole.TEACHER);

        assertThatThrownBy(() -> analysisTaskService.getByCommentId(
                10L,
                31L,
                UserRole.TEACHER
        )).isInstanceOf(CourseAccessDeniedException.class);
    }

    @Test
    void shouldThrowWhenTaskDoesNotExist() {
        given(analysisTaskMapper.selectOne(any(Wrapper.class))).willReturn(null);

        assertThatThrownBy(() -> analysisTaskService.getByCommentId(
                999999L,
                20L,
                UserRole.STUDENT
        )).isInstanceOf(ResourceNotFoundException.class);
    }

    private void prepareTaskAndComment(Long commentOwnerId) {
        AnalysisTask task = new AnalysisTask();
        task.setId(3L);
        task.setTaskNo("1234567890abcdef1234567890abcdef");
        task.setCommentId(10L);
        task.setCourseId(1L);
        task.setStatus("WAITING");
        task.setRetryCount(0);
        CourseComment comment = new CourseComment();
        comment.setId(10L);
        comment.setCourseId(1L);
        comment.setUserId(commentOwnerId);
        given(analysisTaskMapper.selectOne(any(Wrapper.class))).willReturn(task);
        given(commentMapper.selectById(10L)).willReturn(comment);
    }
}
