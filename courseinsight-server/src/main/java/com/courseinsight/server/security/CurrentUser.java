package com.courseinsight.server.security;

import com.courseinsight.server.entity.UserRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

public record CurrentUser(Long id, UserRole role) {

    public static CurrentUser from(Authentication authentication) {
        Long userId = Long.valueOf(authentication.getName());
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String value = authority.getAuthority();
            if (!value.startsWith("ROLE_")) {
                continue;
            }
            try {
                return new CurrentUser(
                        userId,
                        UserRole.valueOf(value.substring("ROLE_".length()))
                );
            } catch (IllegalArgumentException ignored) {
                // Continue looking for a supported application role.
            }
        }
        throw new IllegalStateException("当前登录用户缺少有效角色");
    }
}
