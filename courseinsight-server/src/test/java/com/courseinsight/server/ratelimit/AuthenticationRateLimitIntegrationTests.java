package com.courseinsight.server.ratelimit;

import com.courseinsight.server.config.AuthenticationRateLimitProperties;
import com.courseinsight.server.exception.RateLimitExceededException;
import com.courseinsight.server.testsupport.RedisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RedisIntegrationTest
class AuthenticationRateLimitIntegrationTests {

    @Autowired
    private RedisRateLimiter redisRateLimiter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearRedis() {
        try (RedisConnection connection = redisTemplate
                .getConnectionFactory()
                .getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void shouldLimitManyLoginAccountsFromSameSource() {
        AuthenticationRateLimitService service = service(2, 20, 5);

        service.checkLogin("198.51.100.10", "missing_01");
        service.checkLogin("198.51.100.10", "missing_02");

        assertThatThrownBy(() -> service.checkLogin(
                "198.51.100.10",
                "missing_03"
        )).isInstanceOf(RateLimitExceededException.class)
                .hasMessage("登录请求过于频繁，请稍后再试");
    }

    @Test
    void shouldLimitSameNormalizedAccountAcrossIndependentSources() {
        AuthenticationRateLimitService service = service(20, 2, 5);

        service.checkLogin("198.51.100.11", " Missing_User ");
        service.checkLogin("198.51.100.12", "missing_user");

        assertThatThrownBy(() -> service.checkLogin(
                "198.51.100.13",
                "MISSING_USER"
        )).isInstanceOf(RateLimitExceededException.class)
                .hasMessage("登录请求过于频繁，请稍后再试");
    }

    @Test
    void shouldKeepIndependentLoginSourcesAndAccountsSeparate() {
        AuthenticationRateLimitService service = service(1, 1, 5);

        assertThatCode(() -> {
            service.checkLogin("198.51.100.21", "account_01");
            service.checkLogin("198.51.100.22", "account_02");
        }).doesNotThrowAnyException();
    }

    @Test
    void shouldAllowLegitimateLowRateLogin() {
        AuthenticationRateLimitService service = service(2, 2, 5);

        assertThatCode(() -> service.checkLogin(
                "198.51.100.30",
                "student_01"
        )).doesNotThrowAnyException();
    }

    @Test
    void shouldLimitRegistrationsFromSameSource() {
        AuthenticationRateLimitService service = service(20, 20, 2);

        service.checkRegistration("198.51.100.40");
        service.checkRegistration("198.51.100.40");

        assertThatThrownBy(() -> service.checkRegistration("198.51.100.40"))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessage("注册请求过于频繁，请稍后再试");
    }

    private AuthenticationRateLimitService service(
            int loginSourceMax,
            int loginAccountMax,
            int registrationSourceMax) {
        Duration window = Duration.ofMinutes(1);
        return new AuthenticationRateLimitService(
                redisRateLimiter,
                new AuthenticationRateLimitProperties(
                        new AuthenticationRateLimitProperties.Limit(
                                loginSourceMax,
                                window
                        ),
                        new AuthenticationRateLimitProperties.Limit(
                                loginAccountMax,
                                window
                        ),
                        new AuthenticationRateLimitProperties.Limit(
                                registrationSourceMax,
                                window
                        )
                )
        );
    }
}
