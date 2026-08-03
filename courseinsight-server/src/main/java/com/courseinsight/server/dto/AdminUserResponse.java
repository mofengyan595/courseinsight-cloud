package com.courseinsight.server.dto;

import com.courseinsight.server.entity.AppUser;

import java.time.LocalDateTime;

public record AdminUserResponse(
        Long id,
        String username,
        String displayName,
        String role,
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static AdminUserResponse from(AppUser user) {
        return new AdminUserResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
