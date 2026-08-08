package com.courseinsight.server.testsupport;

import com.courseinsight.server.cache.CourseAnalyticsCache;
import com.courseinsight.server.cache.CourseDetailCache;
import com.courseinsight.server.cache.CoursePopularityRankingCache;
import com.courseinsight.server.ratelimit.RedisRateLimiter;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;

@TestConfiguration(proxyBeanMethods = false)
public class DatabaseIntegrationTestConfiguration {

    @Bean
    RocketMQTemplate rocketMQTemplate() {
        return mock(RocketMQTemplate.class);
    }

    @Bean
    @Primary
    RedisRateLimiter testRedisRateLimiter() {
        return mock(RedisRateLimiter.class);
    }

    @Bean
    @Primary
    CourseDetailCache testCourseDetailCache() {
        return mock(CourseDetailCache.class);
    }

    @Bean
    @Primary
    CourseAnalyticsCache testCourseAnalyticsCache() {
        return mock(CourseAnalyticsCache.class);
    }

    @Bean
    @Primary
    CoursePopularityRankingCache testCoursePopularityRankingCache() {
        return mock(CoursePopularityRankingCache.class);
    }
}
