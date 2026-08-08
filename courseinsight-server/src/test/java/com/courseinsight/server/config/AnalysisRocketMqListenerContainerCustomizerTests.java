package com.courseinsight.server.config;

import com.courseinsight.server.message.AnalysisTaskMessageConsumer;
import com.courseinsight.server.service.AnalysisExecutionService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AnalysisRocketMqListenerContainerCustomizerTests {

    @Test
    void shouldApplyConfiguredBoundToAnalysisConsumerContainer() {
        AnalysisTaskMessageConsumer consumer = new AnalysisTaskMessageConsumer(
                mock(AnalysisExecutionService.class)
        );
        DefaultRocketMQListenerContainer container =
                new DefaultRocketMQListenerContainer();
        container.setRocketMQListener(consumer);
        container.setRocketMQMessageListener(
                AnalysisTaskMessageConsumer.class.getAnnotation(
                        RocketMQMessageListener.class
                )
        );

        new AnalysisRocketMqListenerContainerCustomizer(2)
                .postProcessBeforeInitialization(container, "analysisContainer");

        assertThat(container.getRocketMQMessageListener().consumeThreadNumber())
                .isEqualTo(2);
        assertThat(container.getRocketMQMessageListener().consumeThreadMax())
                .isEqualTo(2);
    }

    @Test
    void shouldRejectNonPositiveThreadNumber() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new AnalysisRocketMqListenerContainerCustomizer(0)
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
