package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.courseinsight.server.dto.UserLoginRequest;
import com.courseinsight.server.dto.UserLoginResponse;
import com.courseinsight.server.entity.AppUser;
import com.courseinsight.server.exception.InvalidCredentialsException;
import com.courseinsight.server.exception.UserDisabledException;
import com.courseinsight.server.mapper.AppUserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserLoginService {

    private final AppUserMapper appUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final String dummyPasswordHash;

    public UserLoginService(
            AppUserMapper appUserMapper,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService) {
        this.appUserMapper = appUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.dummyPasswordHash = passwordEncoder.encode("courseinsight-dummy-password");
    }

    @Transactional(readOnly = true)
    public UserLoginResponse login(UserLoginRequest request) {
        String username = UsernameNormalizer.normalize(request.username());
        AppUser user = appUserMapper.selectOne(
                Wrappers.<AppUser>lambdaQuery()
                        .eq(AppUser::getUsername, username)
        );

        String storedPassword = user == null
                ? dummyPasswordHash
                : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(
                request.password(),
                storedPassword
        );

        if (user == null || !passwordMatches) {
            throw new InvalidCredentialsException("用户名或密码错误");
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new UserDisabledException("用户已被禁用");
        }

        return UserLoginResponse.from(user, jwtTokenService.issue(user));
    }
}
