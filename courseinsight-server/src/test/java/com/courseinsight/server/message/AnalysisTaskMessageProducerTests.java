package com.courseinsight.server.message;

import com.courseinsight.server.exception.MessageQueueException;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AnalysisTaskMessageProducerTests {

    @Mock
    private RocketMQTemplate rocketMQTemplate;

    @Mock
    private SendResult sendResult;

    @Test
    void shouldSendEventWithEventIdAsMessageKey() {
        AnalysisTaskMessageProducer producer =
                new AnalysisTaskMessageProducer(rocketMQTemplate, "courseinsight-analysis", 5000L);
        AnalysisTaskCreatedEvent event = createEvent();
        given(rocketMQTemplate.syncSend(eq("courseinsight-analysis"), any(Message.class), eq(5000L)))
                .willReturn(sendResult);
        given(sendResult.getSendStatus()).willReturn(SendStatus.SEND_OK);
        given(sendResult.getMsgId()).willReturn("message-1");

        String messageId = producer.send(event);

        ArgumentCaptor<Message<?>> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSend(eq("courseinsight-analysis"), messageCaptor.capture(), eq(5000L));
        assertThat(messageId).isEqualTo("message-1");
        assertThat(messageCaptor.getValue().getPayload()).isEqualTo(event);
        assertThat(messageCaptor.getValue().getHeaders().get(RocketMQHeaders.KEYS))
                .isEqualTo("event-1");
    }

    @Test
    void shouldReportBrokerRejectedMessage() {
        AnalysisTaskMessageProducer producer =
                new AnalysisTaskMessageProducer(rocketMQTemplate, "courseinsight-analysis", 5000L);
        given(rocketMQTemplate.syncSend(eq("courseinsight-analysis"), any(Message.class), eq(5000L)))
                .willReturn(sendResult);
        given(sendResult.getSendStatus()).willReturn(SendStatus.FLUSH_DISK_TIMEOUT);

        assertThatThrownBy(() -> producer.send(createEvent()))
                .isInstanceOf(MessageQueueException.class)
                .hasMessage("分析任务消息发送失败");
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
