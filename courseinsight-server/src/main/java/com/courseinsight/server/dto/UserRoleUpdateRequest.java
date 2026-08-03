package com.courseinsight.server.dto;

import com.courseinsight.server.entity.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Locale;

public record UserRoleUpdateRequest(
        @NotBlank(message = "用户角色不能为空")
        @Pattern(
                regexp = "STUDENT|TEACHER|ADMIN",
                message = "用户角色必须是STUDENT、TEACHER或ADMIN"
        )
        String role
) {

    public UserRoleUpdateRequest {
        role = role == null ? null : role.trim().toUpperCase(Locale.ROOT);
    }

    public UserRole toUserRole() {
        return UserRole.valueOf(role);
    }
}
