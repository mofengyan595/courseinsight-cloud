package com.courseinsight.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;

import java.nio.charset.StandardCharsets;

public record UserLoginRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 32, message = "用户名不能超过32个字符")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(max = 64, message = "密码不能超过64个字符")
        String password
) {
    @AssertTrue(message = "密码的 UTF-8 编码不能超过72字节")
    public boolean isPasswordWithinByteLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
