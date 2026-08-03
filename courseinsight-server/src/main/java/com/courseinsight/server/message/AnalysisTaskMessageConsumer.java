package com.courseinsight.server.message;

import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.service.AnalysisExecutionService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "${courseinsight.rocketmq.analysis-topic}",
        consumerGroup = "${courseinsight.rocketmq.analysis-consumer-group}",
        maxReconsumeTimes = 3
)
public class AnalysisTaskMessageConsumer implements RocketMQListener<AnalysisTaskCreatedEvent> {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTaskMessageConsumer.class);

    private final AnalysisExecutionService analysisExecutionService;

    public AnalysisTaskMessageConsumer(AnalysisExecutionService analysisExecutionService) {
        this.analysisExecutionService = analysisExecutionService;
    }

    @Override
    public void onMessage(AnalysisTaskCreatedEvent event) {
        if (event == null
                || event.taskId() == null
                || !AnalysisTaskCreatedEvent.EVENT_TYPE.equals(event.eventType())) {
            log.error("忽略无法处理的分析任务消息: {}", event);
            return;
        }

        try {
            analysisExecutionService.execute(event.taskId());
        } catch (ResourceNotFoundException exception) {
            // 数据永久缺失时继续重试没有意义，记录后确认消息。
            log.error("分析任务消息关联的数据不存在, eventId={}, taskId={}",
                    event.eventId(), event.taskId(), exception);
        }
    }
}
