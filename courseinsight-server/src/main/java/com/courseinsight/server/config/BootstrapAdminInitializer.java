package com.courseinsight.server.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.courseinsight.server.entity.AppUser;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.mapper.AppUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(
        prefix = "courseinsight.bootstrap-admin",
        name = "enabled",
        havingValue = "true"
)
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{3,32}");

    private final BootstrapAdminProperties properties;
    private final AppUserMapper appUserMapper;
    private final PasswordEncoder passwordEncoder;

    public BootstrapAdminInitializer(
            BootstrapAdminProperties properties,
            AppUserMapper appUserMapper,
            PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.appUserMapper = appUserMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String username = normalizeAndValidateUsername(properties.username());
        String password = validatePassword(properties.password());
        String displayName = normalizeAndValidateDisplayName(properties.displayName());

        AppUser existingUser = findByUsername(username);
        if (existingUser != null) {
            verifyExistingAdministrator(existingUser);
            return;
        }

        AppUser administrator = new AppUser();
        administrator.setUsername(username);
        administrator.setPasswordHash(passwordEncoder.encode(password));
        administrator.setDisplayName(displayName);
        administrator.setRole(UserRole.ADMIN.name());
        administrator.setStatus(1);

        try {
            appUserMapper.insert(administrator);
            log.info("Initial administrator account created: username={}", username);
        } catch (DuplicateKeyException exception) {
            AppUser concurrentlyCreatedUser = findByUsername(username);
            if (concurrentlyCreatedUser == null) {
                throw exception;
            }
            verifyExistingAdministrator(concurrentlyCreatedUser);
        }
    }

    private AppUser findByUsername(String username) {
        return appUserMapper.selectOne(
                new LambdaQueryWrapper<AppUser>()
                        .eq(AppUser::getUsername, username)
        );
    }

    private void verifyExistingAdministrator(AppUser user) {
        if (!UserRole.ADMIN.name().equals(user.getRole())) {
            throw new IllegalStateException(
                    "初始化管理员用户名已被非管理员用户占用: " + user.getUsername()
            );
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new IllegalStateException(
                    "初始化管理员账号已被禁用: " + user.getUsername()
            );
        }
    }

    private String normalizeAndValidateUsername(String username) {
        String normalized = username == null
                ? ""
                : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME_PATTERN.matcher(normalized).matches()) {
            throw new IllegalStateException(
                    "BOOTSTRAP_ADMIN_USERNAME 必须为3到32位字母、数字或下划线"
            );
        }
        return normalized;
    }

    private String validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 64) {
            throw new IllegalStateException(
                    "BOOTSTRAP_ADMIN_PASSWORD 长度必须为8到64个字符"
            );
        }
        return password;
    }

    private String normalizeAndValidateDisplayName(String displayName) {
        String normalized = displayName == null ? "" : displayName.trim();
        if (normalized.isEmpty() || normalized.length() > 50) {
            throw new IllegalStateException(
                    "BOOTSTRAP_ADMIN_DISPLAY_NAME 不能为空且不能超过50个字符"
            );
        }
        return normalized;
    }
}
