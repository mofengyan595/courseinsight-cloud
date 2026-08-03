package com.courseinsight.server.service;

import com.courseinsight.server.config.JwtConfig;
import com.courseinsight.server.config.JwtProperties;
import com.courseinsight.server.entity.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import javax.crypto.SecretKey;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenServiceTests {

    @Test
    void shouldIssueSignedTokenWithExpectedClaims() {
        JwtProperties properties = new JwtProperties(
                "0123456789abcdef0123456789abcdef",
                "https://courseinsight.local",
                Duration.ofHours(2)
        );
        JwtConfig config = new JwtConfig();
        SecretKey secretKey = config.jwtSecretKey(properties);
        JwtEncoder jwtEncoder = config.jwtEncoder(secretKey);
        JwtTokenService tokenService = new JwtTokenService(jwtEncoder, properties);

        AppUser user = new AppUser();
        user.setId(7L);
        user.setUsername("student_07");
        user.setRole("STUDENT");

        JwtTokenService.IssuedToken issuedToken = tokenService.issue(user);

        JwtDecoder jwtDecoder = config.jwtDecoder(secretKey, properties);
        Jwt jwt = jwtDecoder.decode(issuedToken.value());

        assertThat(jwt.getIssuer().toString()).isEqualTo("https://courseinsight.local");
        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.getClaimAsString("username")).isEqualTo("student_07");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("STUDENT");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
        assertThat(issuedToken.expiresInSeconds()).isEqualTo(7200);
    }
}
