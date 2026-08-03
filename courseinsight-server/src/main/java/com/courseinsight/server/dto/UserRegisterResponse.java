package com.courseinsight.server.dto;

import com.courseinsight.server.entity.AppUser;

public record UserRegisterResponse(
        Long id,
        String username,
        String displayName,
        String role
) {

    public static UserRegisterResponse from(AppUser user) {
        return new UserRegisterResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole()
        );
    }
}
