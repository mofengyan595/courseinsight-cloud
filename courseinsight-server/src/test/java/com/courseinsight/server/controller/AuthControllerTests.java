package com.courseinsight.server.controller;

import com.courseinsight.server.dto.UserLoginResponse;
import com.courseinsight.server.dto.UserRegisterResponse;
import com.courseinsight.server.exception.GlobalExceptionHandler;
import com.courseinsight.server.exception.InvalidCredentialsException;
import com.courseinsight.server.exception.RateLimitExceededException;
import com.courseinsight.server.exception.UsernameAlreadyExistsException;
import com.courseinsight.server.ratelimit.AuthenticationRateLimitService;
import com.courseinsight.server.service.UserLoginService;
import com.courseinsight.server.service.UserRegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {AuthController.class, GlobalExceptionHandler.class})
class AuthControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRegistrationService registrationService;

    @MockitoBean
    private UserLoginService loginService;

    @MockitoBean
    private AuthenticationRateLimitService authenticationRateLimitService;

    @Test
    void shouldRegisterUser() throws Exception {
        given(registrationService.register(any()))
                .willReturn(new UserRegisterResponse(
                        1L,
                        "student_01",
                        "测试学生",
                        "STUDENT"
                ));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "student_01",
                                  "password": "password123",
                                  "displayName": "测试学生"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.username").value("student_01"))
                .andExpect(jsonPath("$.data.role").value("STUDENT"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void shouldRejectShortPassword() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "student_01",
                                  "password": "short",
                                  "displayName": "测试学生"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("密码长度必须为8到64个字符"));

        verifyNoInteractions(registrationService);
    }

    @Test
    void shouldReturnConflictForDuplicateUsername() throws Exception {
        given(registrationService.register(any()))
                .willThrow(new UsernameAlreadyExistsException("用户名已存在"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "student_01",
                                  "password": "password123",
                                  "displayName": "测试学生"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Test
    void shouldLoginUser() throws Exception {
        given(loginService.login(any()))
                .willReturn(new UserLoginResponse(
                        "signed-token",
                        "Bearer",
                        7200,
                        1L,
                        "student_01",
                        "测试学生",
                        "STUDENT"
                ));

        mockMvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.10");
                            return request;
                        })
                        .header("X-Forwarded-For", "203.0.113.99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "student_01",
                                  "password": "password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").value("signed-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(7200))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.role").value("STUDENT"));

        verify(authenticationRateLimitService).checkLogin(
                "198.51.100.10",
                "student_01"
        );
    }

    @Test
    void shouldRejectInvalidCredentials() throws Exception {
        given(loginService.login(any()))
                .willThrow(new InvalidCredentialsException("用户名或密码错误"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "student_01",
                                  "password": "wrong-password"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void shouldRejectBlankLoginPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "student_01",
                                  "password": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("密码不能为空"));

        verifyNoInteractions(loginService);
    }

    @Test
    void shouldReturnTooManyRequestsWhenLoginLimitIsExceeded() throws Exception {
        willThrow(new RateLimitExceededException("登录请求过于频繁，请稍后再试"))
                .given(authenticationRateLimitService)
                .checkLogin("198.51.100.20", "missing_user");

        mockMvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.20");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "missing_user",
                                  "password": "wrong-password"
                                }
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message")
                        .value("登录请求过于频繁，请稍后再试"));

        verifyNoInteractions(loginService);
    }

    @Test
    void shouldReturnTooManyRequestsWhenRegistrationLimitIsExceeded()
            throws Exception {
        willThrow(new RateLimitExceededException("注册请求过于频繁，请稍后再试"))
                .given(authenticationRateLimitService)
                .checkRegistration("198.51.100.30");

        mockMvc.perform(post("/api/auth/register")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.30");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "student_02",
                                  "password": "password123",
                                  "displayName": "测试学生"
                                }
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message")
                        .value("注册请求过于频繁，请稍后再试"));

        verifyNoInteractions(registrationService);
    }
}
