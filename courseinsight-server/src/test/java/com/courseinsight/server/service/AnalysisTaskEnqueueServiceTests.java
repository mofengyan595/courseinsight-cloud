package com.courseinsight.server.service;

import com.courseinsight.server.dto.AnalysisTaskEnqueueResponse;
import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.AnalysisTaskConflictException;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
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
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AnalysisTaskEnqueueServiceTests {

    @Mock
    private AnalysisTaskMapper analysisTaskMapper;

    @Mock
    private AnalysisOutboxEventMapper outboxEventMapper;

    @Mock
    private CourseManagementAccessService managementAccessService;

    @Mock
    private RedisRateLimiter rateLimiter;

    @InjectMocks
    private AnalysisTaskEnqueueService enqueueService;

    @Test
    void shouldPersistNewOutboxGenerationInsteadOfSendingDirectly() {
        AnalysisTask task = createTask("WAITING");
        given(analysisTaskMapper.selectById(6L)).willReturn(task);
        given(analysisTaskMapper.requeueWithNewGeneration(
                org.mockito.ArgumentMatchers.eq(6L),
                org.mockito.ArgumentMatchers.eq("event-1"),
                any(String.class)
        )).willReturn(1);
        given(outboxEventMapper.insert(any(AnalysisOutboxEvent.class))).willReturn(1);

        AnalysisTaskEnqueueResponse response = enqueueService.enqueue(
                6L,
                11L,
                UserRole.TEACHER
        );

        ArgumentCaptor<AnalysisOutboxEvent> eventCaptor =
                ArgumentCaptor.forClass(AnalysisOutboxEvent.class);
        verify(outboxEventMapper).insert(eventCaptor.capture());
        AnalysisOutboxEvent event = eventCaptor.getValue();
        assertThat(event.getEventId()).hasSize(32).isEqualTo(response.eventId());
        assertThat(event.getTaskId()).isEqualTo(6L);
        assertThat(event.getCommentId()).isEqualTo(13L);
        assertThat(event.getStatus()).isEqualTo("PENDING");
        assertThat(response.messageId()).isNull();
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
                6L, 11L, UserRole.TEACHER
        )).isInstanceOf(AnalysisTaskConflictException.class);

        verifyNoInteractions(outboxEventMapper);
    }

    @Test
    void shouldRejectMissingTask() {
        given(analysisTaskMapper.selectById(999L)).willReturn(null);

        assertThatThrownBy(() -> enqueueService.enqueue(
                999L, 11L, UserRole.TEACHER
        )).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(outboxEventMapper);
    }

    @Test
    void shouldRejectTaskFromAnotherTeachersCourse() {
        given(analysisTaskMapper.selectById(6L)).willReturn(createTask("WAITING"));
        willThrow(new CourseAccessDeniedException("denied"))
                .given(managementAccessService)
                .assertCanManage(14L, 12L, UserRole.TEACHER);

        assertThatThrownBy(() -> enqueueService.enqueue(
                6L, 12L, UserRole.TEACHER
        )).isInstanceOf(CourseAccessDeniedException.class);

        verifyNoInteractions(outboxEventMapper);
    }

    @Test
    void shouldRejectBatchTaskThatBypassesBatchRecovery() {
        AnalysisTask task = createTask("FAILED");
        task.setBatchId(30L);
        given(analysisTaskMapper.selectById(6L)).willReturn(task);

        assertThatThrownBy(() -> enqueueService.enqueue(
                6L, 11L, UserRole.TEACHER
        )).isInstanceOf(AnalysisTaskConflictException.class);

        verifyNoInteractions(outboxEventMapper);
    }

    private AnalysisTask createTask(String status) {
        AnalysisTask task = new AnalysisTask();
        task.setId(6L);
        task.setCommentId(13L);
        task.setCourseId(14L);
        task.setStatus(status);
        task.setCurrentEventId("event-1");
        return task;
    }
}
