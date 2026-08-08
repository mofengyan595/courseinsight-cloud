package com.courseinsight.server.service;

import com.courseinsight.server.dto.AnalysisTaskEnqueueResponse;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.exception.AnalysisTaskConflictException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.message.AnalysisTaskCreatedEvent;
import com.courseinsight.server.message.AnalysisTaskMessageProducer;
import com.courseinsight.server.ratelimit.RateLimitPolicy;
import com.courseinsight.server.ratelimit.RedisRateLimiter;
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
class AnalysisTaskEnqueueServiceTests {

    @Mock
    private AnalysisTaskMapper analysisTaskMapper;

    @Mock
    private AnalysisTaskMessageProducer messageProducer;

    @Mock
    private CourseManagementAccessService managementAccessService;

    @Mock
    private RedisRateLimiter rateLimiter;

    @InjectMocks
    private AnalysisTaskEnqueueService enqueueService;

    @Test
    void shouldEnqueueWaitingTask() {
        AnalysisTask task = createTask("WAITING");
        given(analysisTaskMapper.selectById(6L)).willReturn(task);
        given(messageProducer.send(any(AnalysisTaskCreatedEvent.class))).willReturn("message-1");

        AnalysisTaskEnqueueResponse response = enqueueService.enqueue(
                6L,
                11L,
                UserRole.TEACHER
        );

        ArgumentCaptor<AnalysisTaskCreatedEvent> eventCaptor =
                ArgumentCaptor.forClass(AnalysisTaskCreatedEvent.class);
        verify(messageProducer).send(eventCaptor.capture());
        AnalysisTaskCreatedEvent event = eventCaptor.getValue();
        assertThat(event.eventId()).hasSize(32);
        assertThat(event.taskId()).isEqualTo(6L);
        assertThat(event.commentId()).isEqualTo(13L);
        assertThat(event.eventType()).isEqualTo(AnalysisTaskCreatedEvent.EVENT_TYPE);
        assertThat(response.messageId()).isEqualTo("message-1");
        assertThat(response.eventId()).isEqualTo(event.eventId());
        verify(managementAccessService).assertCanManage(
                14L,
                11L,
                UserRole.TEACHER
        );
        verify(rateLimiter).check(RateLimitPolicy.MANUAL_ANALYSIS, 11L);
    }

    @Test
    void shouldRejectCompletedTask() {
        given(analysisTaskMapper.selectById(6L)).willReturn(createTask("SUCCESS"));

        assertThatThrownBy(() -> enqueueService.enqueue(
                6L,
                11L,
                UserRole.TEACHER
        ))
                .isInstanceOf(AnalysisTaskConflictException.class)
                .hasMessage("分析任务已经完成，无需重复入队");
        verifyNoInteractions(messageProducer);
    }

    @Test
    void shouldRejectMissingTask() {
        given(analysisTaskMapper.selectById(999L)).willReturn(null);

        assertThatThrownBy(() -> enqueueService.enqueue(
                999L,
                11L,
                UserRole.TEACHER
        ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("分析任务不存在");
        verifyNoInteractions(messageProducer);
    }

    @Test
    void shouldRejectTaskFromAnotherTeachersCourse() {
        given(analysisTaskMapper.selectById(6L)).willReturn(createTask("WAITING"));
        org.mockito.BDDMockito.willThrow(
                new CourseAccessDeniedException("无权管理其他教师的课程")
        ).given(managementAccessService).assertCanManage(
                14L,
                12L,
                UserRole.TEACHER
        );

        assertThatThrownBy(() -> enqueueService.enqueue(
                6L,
                12L,
                UserRole.TEACHER
        )).isInstanceOf(CourseAccessDeniedException.class);

        verifyNoInteractions(messageProducer);
    }

    @Test
    void shouldRejectBatchTaskThatBypassesBatchRecovery() {
        AnalysisTask task = createTask("FAILED");
        task.setBatchId(30L);
        given(analysisTaskMapper.selectById(6L)).willReturn(task);

        assertThatThrownBy(() -> enqueueService.enqueue(
                6L,
                11L,
                UserRole.TEACHER
        ))
                .isInstanceOf(AnalysisTaskConflictException.class)
                .hasMessage("批量分析任务请使用批次失败重试接口");

        verifyNoInteractions(messageProducer);
    }

    private AnalysisTask createTask(String status) {
        AnalysisTask task = new AnalysisTask();
        task.setId(6L);
        task.setCommentId(13L);
        task.setCourseId(14L);
        task.setStatus(status);
        return task;
    }
}
