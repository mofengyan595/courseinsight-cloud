package com.courseinsight.server.message;

import com.courseinsight.server.exception.NonRetryableAiServiceException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.exception.RetryableAiServiceException;
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
    void shouldExecuteCurrentGenerationFromMessage() {
        consumer.onMessage(createEvent());

        verify(analysisExecutionService).executeFromMessage(6L, "event-1");
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
        willThrow(new ResourceNotFoundException("missing"))
                .given(analysisExecutionService)
                .executeFromMessage(6L, "event-1");

        consumer.onMessage(createEvent());

        verify(analysisExecutionService).executeFromMessage(6L, "event-1");
    }

    @Test
    void shouldAcknowledgeNonRetryableFailure() {
        willThrow(new NonRetryableAiServiceException("permanent"))
                .given(analysisExecutionService)
                .executeFromMessage(6L, "event-1");

        consumer.onMessage(createEvent());

        verify(analysisExecutionService).executeFromMessage(6L, "event-1");
    }

    @Test
    void shouldRethrowRetryableFailure() {
        willThrow(new RetryableAiServiceException("temporary", null))
                .given(analysisExecutionService)
                .executeFromMessage(6L, "event-1");

        assertThatThrownBy(() -> consumer.onMessage(createEvent()))
                .isInstanceOf(RetryableAiServiceException.class);
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
