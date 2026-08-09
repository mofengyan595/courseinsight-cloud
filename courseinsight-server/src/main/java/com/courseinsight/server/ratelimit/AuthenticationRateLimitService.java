package com.courseinsight.server.ratelimit;

import com.courseinsight.server.config.AuthenticationRateLimitProperties;
import com.courseinsight.server.service.UsernameNormalizer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class AuthenticationRateLimitService {

    static final String LOGIN_KEY_SEGMENT = "authentication-login";
    static final String REGISTRATION_KEY_SEGMENT = "authentication-registration";

    private static final String LOGIN_EXCEEDED_MESSAGE =
            "登录请求过于频繁，请稍后再试";
    private static final String REGISTRATION_EXCEEDED_MESSAGE =
            "注册请求过于频繁，请稍后再试";

    private final RedisRateLimiter rateLimiter;
    private final AuthenticationRateLimitProperties properties;

    public AuthenticationRateLimitService(
            RedisRateLimiter rateLimiter,
            AuthenticationRateLimitProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    public void checkLogin(String sourceAddress, String username) {
        AuthenticationRateLimitProperties.Limit sourceLimit =
                properties.loginSource();
        rateLimiter.check(
                LOGIN_KEY_SEGMENT,
                "source",
                fingerprint(normalizeSource(sourceAddress)),
                sourceLimit.maxRequests(),
                sourceLimit.window(),
                LOGIN_EXCEEDED_MESSAGE
        );

        AuthenticationRateLimitProperties.Limit accountLimit =
                properties.loginAccount();
        rateLimiter.check(
                LOGIN_KEY_SEGMENT,
                "account",
                fingerprint(UsernameNormalizer.normalize(username)),
                accountLimit.maxRequests(),
                accountLimit.window(),
                LOGIN_EXCEEDED_MESSAGE
        );
    }

    public void checkRegistration(String sourceAddress) {
        AuthenticationRateLimitProperties.Limit sourceLimit =
                properties.registrationSource();
        rateLimiter.check(
                REGISTRATION_KEY_SEGMENT,
                "source",
                fingerprint(normalizeSource(sourceAddress)),
                sourceLimit.maxRequests(),
                sourceLimit.window(),
                REGISTRATION_EXCEEDED_MESSAGE
        );
    }

    private String normalizeSource(String sourceAddress) {
        if (sourceAddress == null || sourceAddress.isBlank()) {
            return "unknown";
        }
        return sourceAddress.trim();
    }

    private String fingerprint(String identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(identity.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
