package com.courseinsight.server.message;

import com.courseinsight.server.exception.AiServiceException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.service.AnalysisExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AnalysisTaskMessageConsumerTests {

    @Mock
    private AnalysisExecutionService analysisExecutionService;

    @InjectMocks
    private AnalysisTaskMessageConsumer consumer;

    @Test
    void shouldExecuteTaskFromMessage() {
        consumer.onMessage(createEvent());

        verify(analysisExecutionService).execute(6L);
    }

    @Test
    void shouldIgnoreUnknownEventType() {
        AnalysisTaskCreatedEvent event = new AnalysisTaskCreatedEvent(
                "event-1", 6L, 13L, "UNKNOWN", "2026-08-03T10:00:00Z");

        consumer.onMessage(event);

        verifyNoInteractions(analysisExecutionService);
    }

    @Test
    void shouldAcknowledgeWhenReferencedDataDoesNotExist() {
        willThrow(new ResourceNotFoundException("分析任务不存在"))
                .given(analysisExecutionService).execute(6L);

        consumer.onMessage(createEvent());

        verify(analysisExecutionService).execute(6L);
    }

    @Test
    void shouldRethrowRetryableFailure() {
        willThrow(new AiServiceException("AI 服务调用失败"))
                .given(analysisExecutionService).execute(6L);

        assertThatThrownBy(() -> consumer.onMessage(createEvent()))
                .isInstanceOf(AiServiceException.class)
                .hasMessage("AI 服务调用失败");
    }

    private AnalysisTaskCreatedEvent createEvent() {
        return new AnalysisTaskCreatedEvent(
                "event-1",
                6L,
                13L,
                AnalysisTaskCreatedEvent.EVENT_TYPE,
                "2026-08-03T10:00:00Z"
        );
    }
}
