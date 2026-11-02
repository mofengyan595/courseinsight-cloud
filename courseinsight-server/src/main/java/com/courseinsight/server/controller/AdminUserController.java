package com.courseinsight.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.courseinsight.server.common.ApiResponse;
import jakarta.validation.constraints.Positive;
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
    @Operation(operationId = "listUsers", summary = "管理员分页查询用户")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    public ApiResponse<PageResponse<AdminUserResponse>> page(
            @Valid @ParameterObject @ModelAttribute AdminUserPageQuery query) {
        return ApiResponse.success(adminUserService.page(query));
    }

    @PatchMapping("/{userId}/role")
    @Operation(operationId = "updateUserRole", summary = "管理员修改用户角色")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "用户不存在",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "角色变更冲突",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    public ApiResponse<AdminUserResponse> updateRole(
            @PathVariable @Positive(message = "ID 必须是正整数") Long userId,
            Principal principal,
            @Valid @RequestBody UserRoleUpdateRequest request) {
        return ApiResponse.success(adminUserService.updateRole(
                Long.valueOf(principal.getName()),
                userId,
                request.toUserRole()
        ));
    }
}
