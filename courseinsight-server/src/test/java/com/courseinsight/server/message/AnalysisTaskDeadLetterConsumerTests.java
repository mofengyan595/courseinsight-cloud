package com.courseinsight.server.message;

import com.courseinsight.server.service.AnalysisTaskDeadLetterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AnalysisTaskDeadLetterConsumerTests {

    @Mock
    private AnalysisTaskDeadLetterService deadLetterService;

    @InjectMocks
    private AnalysisTaskDeadLetterConsumer consumer;

    @Test
    void shouldMarkTaskWhenMessageEntersDeadLetterQueue() {
        given(deadLetterService.markDeadLettered(6L)).willReturn(true);

        consumer.onMessage(createEvent());

        verify(deadLetterService).markDeadLettered(6L);
    }

    @Test
    void shouldIgnoreUnknownEventType() {
        AnalysisTaskCreatedEvent event = new AnalysisTaskCreatedEvent(
                "event-1", 6L, 13L, "UNKNOWN", "2026-08-03T10:00:00Z");

        consumer.onMessage(event);

        verifyNoInteractions(deadLetterService);
    }

    @Test
    void shouldAcknowledgeWhenTaskIsMissingOrAlreadySuccessful() {
        given(deadLetterService.markDeadLettered(6L)).willReturn(false);

        consumer.onMessage(createEvent());

        verify(deadLetterService).markDeadLettered(6L);
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
