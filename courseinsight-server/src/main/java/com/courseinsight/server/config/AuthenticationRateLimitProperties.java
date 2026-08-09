package com.courseinsight.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("courseinsight.security.authentication-rate-limit")
public record AuthenticationRateLimitProperties(
        Limit loginSource,
        Limit loginAccount,
        Limit registrationSource) {

    public AuthenticationRateLimitProperties {
        Objects.requireNonNull(loginSource, "loginSource must be configured");
        Objects.requireNonNull(loginAccount, "loginAccount must be configured");
        Objects.requireNonNull(
                registrationSource,
                "registrationSource must be configured"
        );
    }

    public record Limit(int maxRequests, Duration window) {

        public Limit {
            if (maxRequests < 1) {
                throw new IllegalArgumentException("maxRequests must be positive");
            }
            if (window == null
                    || window.isNegative()
                    || window.isZero()
                    || window.toSeconds() < 1
                    || window.toSeconds() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "window must be between 1 second and Integer.MAX_VALUE seconds"
                );
            }
        }
    }
}
