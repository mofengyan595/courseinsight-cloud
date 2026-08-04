package com.courseinsight.server.ratelimit;

import com.courseinsight.server.exception.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisRateLimiter {

    static final String KEY_PREFIX = "rate-limit:";

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisRateLimiter.class);

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> rateLimitScript;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = new DefaultRedisScript<>();
        this.rateLimitScript.setLocation(
                new ClassPathResource("redis/fixed-window-rate-limit.lua")
        );
        this.rateLimitScript.setResultType(Long.class);
    }

    public void check(RateLimitPolicy policy, Long userId) {
        String key = buildKey(policy, userId);
        try {
            Long allowed = redisTemplate.execute(
                    rateLimitScript,
                    List.of(key),
                    String.valueOf(policy.maxRequests()),
                    String.valueOf(policy.windowSeconds())
            );
            if (Long.valueOf(0).equals(allowed)) {
                throw new RateLimitExceededException(policy.exceededMessage());
            }
            if (allowed == null) {
                LOGGER.warn(
                        "Redis rate limit returned no result for policy={}, userId={}, allowing request",
                        policy,
                        userId
                );
            }
        } catch (RateLimitExceededException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Redis rate limit failed for policy={}, userId={}, allowing request",
                    policy,
                    userId,
                    exception
            );
        }
    }

    private String buildKey(RateLimitPolicy policy, Long userId) {
        return KEY_PREFIX + policy.keySegment() + ":user:" + userId;
    }
}
