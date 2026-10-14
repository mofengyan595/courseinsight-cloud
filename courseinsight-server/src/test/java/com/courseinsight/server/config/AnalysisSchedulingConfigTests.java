package com.courseinsight.server.config;

import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.message.AnalysisOutboxAttemptService;
import com.courseinsight.server.message.AnalysisOutboxPublisher;
import com.courseinsight.server.message.AnalysisTaskLeaseRecoveryScheduler;
import com.courseinsight.server.message.AnalysisTaskMessageProducer;
import com.courseinsight.server.metrics.CourseInsightMetrics;
import com.courseinsight.server.service.AnalysisTaskLeaseRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisSchedulingConfigTests {

    @EnableScheduling
    static class SchedulingEnabled {
    }

    @Test
    void recoveryShouldRunWhileScheduledPublisherWaitsForBroker() throws Exception {
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch recoveredDuringSend = new CountDownLatch(1);
        AnalysisOutboxAttemptService attempts = mock(AnalysisOutboxAttemptService.class);
        AnalysisTaskMessageProducer producer = mock(AnalysisTaskMessageProducer.class);
        AnalysisTaskLeaseRecoveryService recovery = mock(AnalysisTaskLeaseRecoveryService.class);
        CourseInsightMetrics metrics = mock(CourseInsightMetrics.class);
        AnalysisOutboxEvent event = new AnalysisOutboxEvent();
        event.setId(1L);
        event.setTaskId(2L);
        event.setCommentId(3L);
        event.setEventId("event");
        when(attempts.findPublishable(anyInt())).thenReturn(List.of(event));
        when(attempts.claim(anyLong(), anyString(), anyLong())).thenReturn(true);
        when(metrics.recordOutboxSend(any())).thenAnswer(call -> {
            Supplier<?> supplier = call.getArgument(0);
            return supplier.get();
        });
        when(producer.send(any())).thenAnswer(call -> {
            sending.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return "message";
        });
        when(recovery.recoverExpired()).thenAnswer(call -> {
            if (sending.getCount() == 0 && release.getCount() == 1) {
                recoveredDuringSend.countDown();
            }
            return 0;
        });

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "courseinsight.outbox.initial-delay-ms", "0",
                "courseinsight.outbox.publish-interval-ms", "60000",
                "courseinsight.analysis.lease-recovery-interval-ms", "20"
        )));
        context.register(SchedulingEnabled.class, AnalysisSchedulingConfig.class,
                AnalysisOutboxPublisher.class, AnalysisTaskLeaseRecoveryScheduler.class);
        context.registerBean(AnalysisOutboxAttemptService.class, () -> attempts);
        context.registerBean(AnalysisTaskMessageProducer.class, () -> producer);
        context.registerBean(AnalysisTaskLeaseRecoveryService.class, () -> recovery);
        context.registerBean(CourseInsightMetrics.class, () -> metrics);
        try {
            context.refresh();
            assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(recoveredDuringSend.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(context.getBean("outboxPublishScheduler")).isNotSameAs(
                    context.getBean("leaseRecoveryScheduler"));
        } finally {
            release.countDown();
            context.close();
        }
    }

    @Test
    void cleanupSchedulerShouldOnlyExistWhenExplicitlyEnabled() {
        try (var context = new AnnotationConfigApplicationContext(AnalysisSchedulingConfig.class)) {
            assertThat(context.containsBean("outboxCleanupScheduler")).isFalse();
            assertThat(context.getBean("taskScheduler", ThreadPoolTaskScheduler.class)
                    .getScheduledThreadPoolExecutor().getCorePoolSize())
                    .isEqualTo(1);
        }
    }
}
