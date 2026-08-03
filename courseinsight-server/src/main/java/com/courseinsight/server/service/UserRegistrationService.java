package com.courseinsight.server.service;

import com.courseinsight.server.dto.UserRegisterRequest;
import com.courseinsight.server.dto.UserRegisterResponse;
import com.courseinsight.server.entity.AppUser;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.UsernameAlreadyExistsException;
import com.courseinsight.server.mapper.AppUserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class UserRegistrationService {

    private final AppUserMapper appUserMapper;
    private final PasswordEncoder passwordEncoder;

    public UserRegistrationService(
            AppUserMapper appUserMapper,
            PasswordEncoder passwordEncoder) {
        this.appUserMapper = appUserMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserRegisterResponse register(UserRegisterRequest request) {
        AppUser user = new AppUser();
        user.setUsername(request.username().trim().toLowerCase(Locale.ROOT));
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName().trim());
        user.setRole(UserRole.STUDENT.name());
        user.setStatus(1);

        try {
            appUserMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new UsernameAlreadyExistsException("用户名已存在");
        }

        return UserRegisterResponse.from(user);
    }
}
