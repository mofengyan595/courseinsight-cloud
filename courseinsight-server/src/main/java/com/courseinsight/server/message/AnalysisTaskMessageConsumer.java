package com.courseinsight.server.message;

import com.courseinsight.server.exception.NonRetryableAiServiceException;
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
        consumeThreadNumber = 1,
        consumeThreadMax = 1,
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
                || event.eventId() == null
                || event.taskId() == null
                || !AnalysisTaskCreatedEvent.EVENT_TYPE.equals(event.eventType())) {
            log.error("Ignoring invalid analysis task message: {}", event);
            return;
        }

        try {
            analysisExecutionService.executeFromMessage(
                    event.taskId(),
                    event.eventId()
            );
        } catch (NonRetryableAiServiceException exception) {
            // The owner CAS already persisted a terminal failure. Acknowledge so
            // RocketMQ does not repeat a permanently invalid request.
            log.error(
                    "Analysis task failed permanently, eventId={}, taskId={}",
                    event.eventId(),
                    event.taskId(),
                    exception
            );
        } catch (ResourceNotFoundException exception) {
            log.error(
                    "Analysis task message references missing data, eventId={}, taskId={}",
                    event.eventId(),
                    event.taskId(),
                    exception
            );
        }
    }
}
