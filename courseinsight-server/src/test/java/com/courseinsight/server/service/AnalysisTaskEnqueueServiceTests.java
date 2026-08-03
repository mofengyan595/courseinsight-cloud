package com.courseinsight.server.service;

import com.courseinsight.server.dto.AnalysisTaskEnqueueResponse;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.exception.AnalysisTaskConflictException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.message.AnalysisTaskCreatedEvent;
import com.courseinsight.server.message.AnalysisTaskMessageProducer;
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

    @InjectMocks
    private AnalysisTaskEnqueueService enqueueService;

    @Test
    void shouldEnqueueWaitingTask() {
        AnalysisTask task = createTask("WAITING");
        given(analysisTaskMapper.selectById(6L)).willReturn(task);
        given(messageProducer.send(any(AnalysisTaskCreatedEvent.class))).willReturn("message-1");

        AnalysisTaskEnqueueResponse response = enqueueService.enqueue(6L);

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
    }

    @Test
    void shouldRejectCompletedTask() {
        given(analysisTaskMapper.selectById(6L)).willReturn(createTask("SUCCESS"));

        assertThatThrownBy(() -> enqueueService.enqueue(6L))
                .isInstanceOf(AnalysisTaskConflictException.class)
                .hasMessage("分析任务已经完成，无需重复入队");
        verifyNoInteractions(messageProducer);
    }

    @Test
    void shouldRejectMissingTask() {
        given(analysisTaskMapper.selectById(999L)).willReturn(null);

        assertThatThrownBy(() -> enqueueService.enqueue(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("分析任务不存在");
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
