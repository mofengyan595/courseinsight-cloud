package com.courseinsight.server.service;

import com.courseinsight.server.dto.UserRegisterRequest;
import com.courseinsight.server.dto.UserRegisterResponse;
import com.courseinsight.server.entity.AppUser;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.UsernameAlreadyExistsException;
import com.courseinsight.server.mapper.AppUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserRegistrationServiceTests {

    @Mock
    private AppUserMapper appUserMapper;

    private PasswordEncoder passwordEncoder;
    private UserRegistrationService registrationService;

    @BeforeEach
    void setUp() {
        passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        registrationService = new UserRegistrationService(appUserMapper, passwordEncoder);
    }

    @Test
    void shouldRegisterStudentWithEncodedPassword() {
        given(appUserMapper.insert(any(AppUser.class)))
                .willAnswer(invocation -> {
                    AppUser user = invocation.getArgument(0);
                    user.setId(1L);
                    return 1;
                });

        UserRegisterResponse response = registrationService.register(
                new UserRegisterRequest("Student_01", "password123", "测试学生")
        );

        ArgumentCaptor<AppUser> userCaptor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserMapper).insert(userCaptor.capture());
        AppUser savedUser = userCaptor.getValue();

        assertThat(savedUser.getUsername()).isEqualTo("student_01");
        assertThat(savedUser.getPasswordHash()).startsWith("{bcrypt}");
        assertThat(savedUser.getPasswordHash()).isNotEqualTo("password123");
        assertThat(passwordEncoder.matches("password123", savedUser.getPasswordHash()))
                .isTrue();
        assertThat(savedUser.getRole()).isEqualTo(UserRole.STUDENT.name());
        assertThat(savedUser.getStatus()).isEqualTo(1);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.username()).isEqualTo("student_01");
        assertThat(response.role()).isEqualTo(UserRole.STUDENT.name());
    }

    @Test
    void shouldRejectDuplicateUsername() {
        willThrow(new DuplicateKeyException("duplicate username"))
                .given(appUserMapper)
                .insert(any(AppUser.class));

        assertThatThrownBy(() -> registrationService.register(
                new UserRegisterRequest("student_01", "password123", "测试学生")
        )).isInstanceOf(UsernameAlreadyExistsException.class)
                .hasMessage("用户名已存在");
    }
}
