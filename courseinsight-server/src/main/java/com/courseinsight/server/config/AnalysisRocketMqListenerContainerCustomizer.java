package com.courseinsight.server.config;

import com.courseinsight.server.message.AnalysisTaskMessageConsumer;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

@Component
public class AnalysisRocketMqListenerContainerCustomizer implements BeanPostProcessor {

    private final int consumeThreadNumber;

    public AnalysisRocketMqListenerContainerCustomizer(
            @Value("${courseinsight.rocketmq.analysis-consume-thread-number:1}")
            int consumeThreadNumber) {
        if (consumeThreadNumber <= 0) {
            throw new IllegalArgumentException(
                    "analysis-consume-thread-number must be positive"
            );
        }
        this.consumeThreadNumber = consumeThreadNumber;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (!(bean instanceof DefaultRocketMQListenerContainer container)
                || !(container.getRocketMQListener()
                instanceof AnalysisTaskMessageConsumer)) {
            return bean;
        }

        RocketMQMessageListener original = container.getRocketMQMessageListener();
        RocketMQMessageListener configured = (RocketMQMessageListener)
                Proxy.newProxyInstance(
                        RocketMQMessageListener.class.getClassLoader(),
                        new Class<?>[]{RocketMQMessageListener.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("consumeThreadNumber")
                                    || method.getName().equals("consumeThreadMax")) {
                                return consumeThreadNumber;
                            }
                            try {
                                return method.invoke(original, arguments);
                            } catch (InvocationTargetException exception) {
                                throw exception.getCause();
                            }
                        }
                );
        container.setRocketMQMessageListener(configured);
        return bean;
    }
}
