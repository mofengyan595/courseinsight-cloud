package com.courseinsight.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserRegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 32, message = "用户名长度必须为3到32个字符")
        @Pattern(regexp = "[A-Za-z0-9_]+", message = "用户名只能包含字母、数字和下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度必须为8到64个字符")
        String password,

        @NotBlank(message = "显示名称不能为空")
        @Size(max = 50, message = "显示名称不能超过50个字符")
        String displayName
) {
}
