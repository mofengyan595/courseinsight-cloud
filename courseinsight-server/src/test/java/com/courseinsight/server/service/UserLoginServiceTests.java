package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.courseinsight.server.dto.UserLoginRequest;
import com.courseinsight.server.dto.UserLoginResponse;
import com.courseinsight.server.entity.AppUser;
import com.courseinsight.server.exception.InvalidCredentialsException;
import com.courseinsight.server.exception.UserDisabledException;
import com.courseinsight.server.mapper.AppUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UserLoginServiceTests {

    @Mock
    private AppUserMapper appUserMapper;

    @Mock
    private JwtTokenService jwtTokenService;

    private PasswordEncoder passwordEncoder;
    private UserLoginService loginService;

    @BeforeEach
    void setUp() {
        passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        loginService = new UserLoginService(
                appUserMapper,
                passwordEncoder,
                jwtTokenService
        );
    }

    @Test
    void shouldLoginWithValidCredentials() {
        AppUser user = enabledUser();
        given(appUserMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<AppUser>>any()))
                .willReturn(user);
        given(jwtTokenService.issue(user))
                .willReturn(new JwtTokenService.IssuedToken("signed-token", 7200));

        UserLoginResponse response = loginService.login(
                new UserLoginRequest(" Student_01 ", "password123")
        );

        assertThat(response.accessToken()).isEqualTo("signed-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(7200);
        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.username()).isEqualTo("student_01");
        assertThat(response.role()).isEqualTo("STUDENT");
    }

    @Test
    void shouldRejectWrongPassword() {
        given(appUserMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<AppUser>>any()))
                .willReturn(enabledUser());

        assertThatThrownBy(() -> loginService.login(
                new UserLoginRequest("student_01", "wrong-password")
        )).isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("用户名或密码错误");

        verifyNoInteractions(jwtTokenService);
    }

    @Test
    void shouldUseSameErrorWhenUserDoesNotExist() {
        given(appUserMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<AppUser>>any()))
                .willReturn(null);

        assertThatThrownBy(() -> loginService.login(
                new UserLoginRequest("missing_user", "password123")
        )).isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("用户名或密码错误");

        verifyNoInteractions(jwtTokenService);
    }

    @Test
    void shouldRejectDisabledUser() {
        AppUser user = enabledUser();
        user.setStatus(0);
        given(appUserMapper.selectOne(org.mockito.ArgumentMatchers.<Wrapper<AppUser>>any()))
                .willReturn(user);

        assertThatThrownBy(() -> loginService.login(
                new UserLoginRequest("student_01", "password123")
        )).isInstanceOf(UserDisabledException.class)
                .hasMessage("用户已被禁用");

        verifyNoInteractions(jwtTokenService);
    }

    private AppUser enabledUser() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("student_01");
        user.setPasswordHash(passwordEncoder.encode("password123"));
        user.setDisplayName("测试学生");
        user.setRole("STUDENT");
        user.setStatus(1);
        return user;
    }
}
