package com.courseinsight.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.courseinsight.server.common.ApiResponse;
import com.courseinsight.server.dto.UserLoginRequest;
import com.courseinsight.server.dto.UserLoginResponse;
import com.courseinsight.server.dto.UserRegisterRequest;
import com.courseinsight.server.dto.UserRegisterResponse;
import com.courseinsight.server.ratelimit.AuthenticationRateLimitService;
import com.courseinsight.server.service.UserLoginService;
import com.courseinsight.server.service.UserRegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRegistrationService registrationService;
    private final UserLoginService loginService;
    private final AuthenticationRateLimitService authenticationRateLimitService;

    public AuthController(
            UserRegistrationService registrationService,
            UserLoginService loginService,
            AuthenticationRateLimitService authenticationRateLimitService) {
        this.registrationService = registrationService;
        this.loginService = loginService;
        this.authenticationRateLimitService = authenticationRateLimitService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "registerUser", summary = "注册学生账号")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "用户名已存在",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "注册过于频繁",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    public ApiResponse<UserRegisterResponse> register(
            @Valid @RequestBody UserRegisterRequest request,
            HttpServletRequest servletRequest) {
        authenticationRateLimitService.checkRegistration(
                servletRequest.getRemoteAddr()
        );
        return ApiResponse.success(registrationService.register(request));
    }

    @PostMapping("/login")
    @Operation(operationId = "loginUser", summary = "登录并获取 JWT")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "用户名或密码错误",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "用户已停用",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "登录过于频繁",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    public ApiResponse<UserLoginResponse> login(
            @Valid @RequestBody UserLoginRequest request,
            HttpServletRequest servletRequest) {
        authenticationRateLimitService.checkLogin(
                servletRequest.getRemoteAddr(),
                request.username()
        );
        return ApiResponse.success(loginService.login(request));
    }
}
