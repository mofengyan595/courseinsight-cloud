package com.courseinsight.server.testsupport;

import com.courseinsight.server.cache.CoursePopularityRankingCache;
import com.courseinsight.server.ratelimit.RedisRateLimiter;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.ContextConfiguration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Tag("integration")
@DataRedisTest
@ContextConfiguration(classes = {
        RedisRateLimiter.class,
        CoursePopularityRankingCache.class
})
@ImportTestcontainers(RedisTestContainers.class)
public @interface RedisIntegrationTest {
}
