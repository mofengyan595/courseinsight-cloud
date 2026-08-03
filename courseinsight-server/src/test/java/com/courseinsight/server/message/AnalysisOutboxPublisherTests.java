package com.courseinsight.server.message;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.entity.AnalysisOutboxStatus;
import com.courseinsight.server.exception.MessageQueueException;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class AnalysisOutboxPublisherTests {

    @Mock
    private AnalysisOutboxEventMapper outboxEventMapper;

    @Mock
    private AnalysisTaskMessageProducer messageProducer;

    private AnalysisOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new AnalysisOutboxPublisher(
                outboxEventMapper,
                messageProducer,
                20,
                30,
                300
        );
    }

    @Test
    void shouldPublishAndMarkEventSent() {
        given(outboxEventMapper.selectList(any(Wrapper.class)))
                .willReturn(List.of(createEvent()));
        given(outboxEventMapper.update(any(), any(Wrapper.class))).willReturn(1);
        given(messageProducer.send(any(AnalysisTaskCreatedEvent.class)))
                .willReturn("message-1");

        publisher.publishPending();

        verify(messageProducer).send(any(AnalysisTaskCreatedEvent.class));
        verify(outboxEventMapper, times(2)).update(any(), any(Wrapper.class));
    }

    @Test
    void shouldMarkEventFailedWhenRocketMqIsUnavailable() {
        given(outboxEventMapper.selectList(any(Wrapper.class)))
                .willReturn(List.of(createEvent()));
        given(outboxEventMapper.update(any(), any(Wrapper.class))).willReturn(1);
        willThrow(new MessageQueueException("RocketMQ 不可用"))
                .given(messageProducer).send(any(AnalysisTaskCreatedEvent.class));

        publisher.publishPending();

        verify(messageProducer).send(any(AnalysisTaskCreatedEvent.class));
        verify(outboxEventMapper, times(2)).update(any(), any(Wrapper.class));
    }

    @Test
    void shouldSkipEventWhenAnotherWorkerHasClaimedIt() {
        given(outboxEventMapper.selectList(any(Wrapper.class)))
                .willReturn(List.of(createEvent()));
        given(outboxEventMapper.update(any(), any(Wrapper.class))).willReturn(0);

        publisher.publishPending();

        verifyNoInteractions(messageProducer);
    }

    private AnalysisOutboxEvent createEvent() {
        AnalysisOutboxEvent event = new AnalysisOutboxEvent();
        event.setId(1L);
        event.setEventId("1234567890abcdef1234567890abcdef");
        event.setTaskId(6L);
        event.setCommentId(13L);
        event.setEventType(AnalysisTaskCreatedEvent.EVENT_TYPE);
        event.setStatus(AnalysisOutboxStatus.PENDING.name());
        event.setRetryCount(0);
        event.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        return event;
    }
}
