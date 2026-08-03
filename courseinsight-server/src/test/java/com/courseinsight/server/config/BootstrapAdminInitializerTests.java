package com.courseinsight.server.config;

import com.courseinsight.server.entity.AppUser;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.mapper.AppUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BootstrapAdminInitializerTests {

    @Mock
    private AppUserMapper appUserMapper;

    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Test
    void shouldCreateAdministratorWithEncodedPassword() {
        given(appUserMapper.selectOne(any())).willReturn(null);
        BootstrapAdminInitializer initializer = initializer(
                "Initial_Admin",
                "strong-password",
                "初始管理员"
        );

        initializer.run(null);

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserMapper).insert(captor.capture());
        AppUser savedUser = captor.getValue();
        assertThat(savedUser.getUsername()).isEqualTo("initial_admin");
        assertThat(savedUser.getDisplayName()).isEqualTo("初始管理员");
        assertThat(savedUser.getRole()).isEqualTo(UserRole.ADMIN.name());
        assertThat(savedUser.getStatus()).isEqualTo(1);
        assertThat(savedUser.getPasswordHash()).startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches("strong-password", savedUser.getPasswordHash()))
                .isTrue();
    }

    @Test
    void shouldKeepExistingEnabledAdministratorUnchanged() {
        AppUser existingAdministrator = user(UserRole.ADMIN, 1);
        given(appUserMapper.selectOne(any())).willReturn(existingAdministrator);

        initializer("admin", "strong-password", "管理员").run(null);

        verify(appUserMapper, never()).insert(any(AppUser.class));
    }

    @Test
    void shouldRejectUsernameOwnedByNonAdministrator() {
        AppUser existingStudent = user(UserRole.STUDENT, 1);
        given(appUserMapper.selectOne(any())).willReturn(existingStudent);

        assertThatThrownBy(() -> initializer(
                "admin",
                "strong-password",
                "管理员"
        ).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非管理员用户占用");

        verify(appUserMapper, never()).insert(any(AppUser.class));
    }

    @Test
    void shouldRejectInvalidBootstrapPassword() {
        assertThatThrownBy(() -> initializer(
                "admin",
                "short",
                "管理员"
        ).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOOTSTRAP_ADMIN_PASSWORD");

        verify(appUserMapper, never()).insert(any(AppUser.class));
    }

    private BootstrapAdminInitializer initializer(
            String username,
            String password,
            String displayName) {
        BootstrapAdminProperties properties = new BootstrapAdminProperties(
                true,
                username,
                password,
                displayName
        );
        return new BootstrapAdminInitializer(properties, appUserMapper, passwordEncoder);
    }

    private AppUser user(UserRole role, int status) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setDisplayName("管理员");
        user.setPasswordHash("unused");
        user.setRole(role.name());
        user.setStatus(status);
        return user;
    }
}
