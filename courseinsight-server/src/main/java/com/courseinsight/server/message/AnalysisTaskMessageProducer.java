package com.courseinsight.server.message;

import com.courseinsight.server.exception.MessageQueueException;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
public class AnalysisTaskMessageProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final String topic;
    private final long sendTimeoutMs;

    public AnalysisTaskMessageProducer(
            RocketMQTemplate rocketMQTemplate,
            @Value("${courseinsight.rocketmq.analysis-topic}") String topic,
            @Value("${courseinsight.rocketmq.send-timeout-ms:5000}") long sendTimeoutMs) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.topic = topic;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    public String send(AnalysisTaskCreatedEvent event) {
        Message<AnalysisTaskCreatedEvent> message = MessageBuilder.withPayload(event)
                .setHeader(RocketMQHeaders.KEYS, event.eventId())
                .build();

        try {
            SendResult result = rocketMQTemplate.syncSend(topic, message, sendTimeoutMs);
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new MessageQueueException("分析任务消息发送失败");
            }
            return result.getMsgId();
        } catch (MessageQueueException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MessageQueueException("RocketMQ 暂时不可用，分析任务未入队", exception);
        }
    }
}
