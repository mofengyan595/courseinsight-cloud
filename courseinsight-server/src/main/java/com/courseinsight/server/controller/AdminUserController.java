package com.courseinsight.server.controller;

import com.courseinsight.server.common.ApiResponse;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.AdminUserPageQuery;
import com.courseinsight.server.dto.AdminUserResponse;
import com.courseinsight.server.dto.UserRoleUpdateRequest;
import com.courseinsight.server.service.AdminUserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminUserResponse>> page(
            @Valid @ModelAttribute AdminUserPageQuery query) {
        return ApiResponse.success(adminUserService.page(query));
    }

    @PatchMapping("/{userId}/role")
    public ApiResponse<AdminUserResponse> updateRole(
            @PathVariable Long userId,
            Principal principal,
            @Valid @RequestBody UserRoleUpdateRequest request) {
        return ApiResponse.success(adminUserService.updateRole(
                Long.valueOf(principal.getName()),
                userId,
                request.toUserRole()
        ));
    }
}
