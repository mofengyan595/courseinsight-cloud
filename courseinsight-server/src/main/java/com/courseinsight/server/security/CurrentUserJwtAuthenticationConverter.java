package com.courseinsight.server.security;

import com.courseinsight.server.entity.AppUser;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.mapper.AppUserMapper;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

public final class CurrentUserJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AppUserMapper appUserMapper;

    public CurrentUserJwtAuthenticationConverter(AppUserMapper appUserMapper) {
        this.appUserMapper = appUserMapper;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Long userId = parseUserId(jwt.getSubject());
        AppUser user = appUserMapper.selectById(userId);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw invalidToken();
        }

        UserRole currentRole;
        try {
            currentRole = UserRole.valueOf(user.getRole());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalidToken();
        }

        return new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("ROLE_" + currentRole.name())),
                userId.toString()
        );
    }

    private Long parseUserId(String subject) {
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException | NullPointerException exception) {
            throw invalidToken();
        }
    }

    private OAuth2AuthenticationException invalidToken() {
        return new OAuth2AuthenticationException(
                new OAuth2Error(BearerTokenErrorCodes.INVALID_TOKEN),
                "Invalid bearer token"
        );
    }
}
