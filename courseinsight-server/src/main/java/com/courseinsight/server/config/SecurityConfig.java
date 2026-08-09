package com.courseinsight.server.config;

import com.courseinsight.server.common.ApiResponse;
import com.courseinsight.server.mapper.AppUserMapper;
import com.courseinsight.server.security.CurrentUserJwtAuthenticationConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableConfigurationProperties(AuthenticationRateLimitProperties.class)
public class SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http)
            throws Exception {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().permitAll());

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter)
            throws Exception {
        AuthenticationEntryPoint authenticationEntryPoint = (request, response, exception) ->
                writeError(
                        response,
                        objectMapper,
                        HttpStatus.UNAUTHORIZED,
                        "未登录或令牌无效"
                );
        AccessDeniedHandler accessDeniedHandler = (request, response, exception) ->
                writeError(
                        response,
                        objectMapper,
                        HttpStatus.FORBIDDEN,
                        "权限不足"
                );

        http
                .securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/health", "/api/auth/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/courses")
                        .hasAnyRole("TEACHER", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/courses/**")
                        .hasAnyRole("TEACHER", "ADMIN")
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/courses/*/analytics/summary"
                        ).hasAnyRole("TEACHER", "ADMIN")
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/analysis-tasks/*/execute",
                                "/api/analysis-tasks/*/enqueue",
                                "/api/courses/*/analysis-batches",
                                "/api/analysis-batches/*/retry-failed"
                        ).hasAnyRole("TEACHER", "ADMIN")
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/analysis-batches/*",
                                "/api/analysis-batches/*/results",
                                "/api/analysis-batches/*/export"
                        ).hasAnyRole("TEACHER", "ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                jwtAuthenticationConverter
                        )));

        return http.build();
    }

    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter(
            AppUserMapper appUserMapper) {
        return new CurrentUserJwtAuthenticationConverter(appUserMapper);
    }

    private void writeError(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            HttpStatus status,
            String message) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiResponse.error(status.value(), message)
        );
    }
}
