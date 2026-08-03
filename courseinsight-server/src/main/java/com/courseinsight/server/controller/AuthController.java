package com.courseinsight.server.controller;

import com.courseinsight.server.common.ApiResponse;
import com.courseinsight.server.dto.UserLoginRequest;
import com.courseinsight.server.dto.UserLoginResponse;
import com.courseinsight.server.dto.UserRegisterRequest;
import com.courseinsight.server.dto.UserRegisterResponse;
import com.courseinsight.server.service.UserLoginService;
import com.courseinsight.server.service.UserRegistrationService;
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

    public AuthController(
            UserRegistrationService registrationService,
            UserLoginService loginService) {
        this.registrationService = registrationService;
        this.loginService = loginService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserRegisterResponse> register(
            @Valid @RequestBody UserRegisterRequest request) {
        return ApiResponse.success(registrationService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<UserLoginResponse> login(
            @Valid @RequestBody UserLoginRequest request) {
        return ApiResponse.success(loginService.login(request));
    }
}
