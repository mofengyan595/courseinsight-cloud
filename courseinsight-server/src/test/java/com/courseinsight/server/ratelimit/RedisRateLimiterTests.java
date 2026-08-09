package com.courseinsight.server.ratelimit;

import com.courseinsight.server.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RedisRateLimiterTests {

    @Mock
    private StringRedisTemplate redisTemplate;

    private RedisRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RedisRateLimiter(redisTemplate);
    }

    @Test
    void shouldAllowRequestWithinLimit() {
        given(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("rate-limit:comment-submission:user:7")),
                eq("5"),
                eq("60")
        )).willReturn(1L);

        assertThatCode(() -> rateLimiter.check(
                RateLimitPolicy.COMMENT_SUBMISSION,
                7L
        )).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectRequestOverLimit() {
        given(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("rate-limit:manual-analysis:user:11")),
                eq("10"),
                eq("60")
        )).willReturn(0L);

        assertThatThrownBy(() -> rateLimiter.check(
                RateLimitPolicy.MANUAL_ANALYSIS,
                11L
        ))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("AI 分析操作过于频繁，请稍后再试");
    }

    @Test
    void shouldFailOpenWhenRedisIsUnavailable() {
        given(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("rate-limit:comment-submission:user:7")),
                eq("5"),
                eq("60")
        )).willThrow(new DataAccessResourceFailureException("Redis unavailable"));

        assertThatCode(() -> rateLimiter.check(
                RateLimitPolicy.COMMENT_SUBMISSION,
                7L
        )).doesNotThrowAnyException();
    }

    @Test
    void shouldFailOpenForAuthenticationLimitWhenRedisIsUnavailable() {
        given(redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("rate-limit:authentication-login:source:fingerprint")),
                eq("20"),
                eq("60")
        )).willThrow(new DataAccessResourceFailureException("Redis unavailable"));

        assertThatCode(() -> rateLimiter.check(
                "authentication-login",
                "source",
                "fingerprint",
                20,
                Duration.ofMinutes(1),
                "登录请求过于频繁，请稍后再试"
        )).doesNotThrowAnyException();
    }
}
