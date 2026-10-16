package com.courseinsight.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;

import java.util.Locale;

public record AdminUserPageQuery(
        @Min(value = 1, message = "页码必须大于等于1")
        Integer page,

        @Min(value = 1, message = "每页条数必须大于等于1")
        @Max(value = 100, message = "每页条数不能超过100")
        Integer size,

        @Size(max = 100, message = "搜索关键词不能超过100个字符")
        String keyword,

        @Pattern(
                regexp = "STUDENT|TEACHER|ADMIN",
                message = "用户角色必须是STUDENT、TEACHER或ADMIN"
        )
        String role,

        @Min(value = 0, message = "用户状态只能是0或1")
        @Max(value = 1, message = "用户状态只能是0或1")
        Integer status,
        @Positive(message = "用户 ID 必须是正整数") Long userId
) {
    public AdminUserPageQuery(Integer page, Integer size, String keyword, String role, Integer status) {
        this(page, size, keyword, role, status, null);
    }

    public AdminUserPageQuery {
        page = page == null ? 1 : page;
        size = size == null ? 10 : size;
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        role = role == null || role.isBlank()
                ? null
                : role.trim().toUpperCase(Locale.ROOT);
    }
}
