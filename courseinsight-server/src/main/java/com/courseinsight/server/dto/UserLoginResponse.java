package com.courseinsight.server.dto;

import com.courseinsight.server.entity.AppUser;
import com.courseinsight.server.service.JwtTokenService;

public record UserLoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        Long userId,
        String username,
        String displayName,
        String role
) {

    public static UserLoginResponse from(
            AppUser user,
            JwtTokenService.IssuedToken issuedToken) {
        return new UserLoginResponse(
                issuedToken.value(),
                "Bearer",
                issuedToken.expiresInSeconds(),
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole()
        );
    }
}
