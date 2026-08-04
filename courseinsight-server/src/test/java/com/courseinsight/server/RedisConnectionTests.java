package com.courseinsight.server;

import com.courseinsight.server.exception.RateLimitExceededException;
import com.courseinsight.server.ratelimit.RateLimitPolicy;
import com.courseinsight.server.ratelimit.RedisRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class RedisConnectionTests {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisRateLimiter rateLimiter;

    @Test
    void connectsToRedis() {
        String key = "test:redis:" + UUID.randomUUID();
        try {
            redisTemplate.opsForValue().set(key, "ok", Duration.ofSeconds(30));
            assertEquals("ok", redisTemplate.opsForValue().get(key));
        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    void luaRateLimiterRejectsRequestAfterFixedWindowLimit() {
        Long userId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        String key = "rate-limit:comment-submission:user:" + userId;
        try {
            for (int request = 0; request < 5; request++) {
                assertThatCode(() -> rateLimiter.check(
                        RateLimitPolicy.COMMENT_SUBMISSION,
                        userId
                )).doesNotThrowAnyException();
            }

            assertThatThrownBy(() -> rateLimiter.check(
                    RateLimitPolicy.COMMENT_SUBMISSION,
                    userId
            ))
                    .isInstanceOf(RateLimitExceededException.class)
                    .hasMessage("提交评价过于频繁，请稍后再试");

            assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS))
                    .isBetween(1L, 60L);
        } finally {
            redisTemplate.delete(key);
        }
    }
}
