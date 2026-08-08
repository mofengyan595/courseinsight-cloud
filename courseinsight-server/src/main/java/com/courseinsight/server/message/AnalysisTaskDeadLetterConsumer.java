package com.courseinsight.server.message;

import com.courseinsight.server.service.AnalysisTaskDeadLetterService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "${courseinsight.rocketmq.analysis-dead-letter-topic}",
        consumerGroup = "${courseinsight.rocketmq.analysis-dead-letter-consumer-group}"
)
public class AnalysisTaskDeadLetterConsumer implements RocketMQListener<AnalysisTaskCreatedEvent> {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTaskDeadLetterConsumer.class);

    private final AnalysisTaskDeadLetterService deadLetterService;

    public AnalysisTaskDeadLetterConsumer(AnalysisTaskDeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    @Override
    public void onMessage(AnalysisTaskCreatedEvent event) {
        if (event == null
                || event.taskId() == null
                || !AnalysisTaskCreatedEvent.EVENT_TYPE.equals(event.eventType())) {
            log.error("忽略无法处理的死信分析任务消息: {}", event);
            return;
        }

        boolean marked = deadLetterService.markDeadLettered(
                event.taskId(),
                event.eventId()
        );
        if (marked) {
            log.error("分析任务消息已进入死信队列, eventId={}, taskId={}",
                    event.eventId(), event.taskId());
        } else {
            log.warn("死信消息对应任务不存在或已完成, eventId={}, taskId={}",
                    event.eventId(), event.taskId());
        }
    }
}
