package com.courseinsight.server.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class AnalysisSchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler outboxPublishScheduler() {
        return scheduler("outbox-schedule-");
    }

    @Bean(name = {"taskScheduler", "leaseRecoveryScheduler"})
    public ThreadPoolTaskScheduler leaseRecoveryScheduler() {
        return scheduler("lease-recovery-");
    }

    @Bean
    @ConditionalOnProperty(name = "courseinsight.outbox.cleanup.enabled", havingValue = "true")
    public ThreadPoolTaskScheduler outboxCleanupScheduler() {
        return scheduler("outbox-cleanup-");
    }

    private ThreadPoolTaskScheduler scheduler(String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
