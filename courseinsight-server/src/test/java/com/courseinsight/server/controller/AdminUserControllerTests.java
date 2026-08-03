package com.courseinsight.server.controller;

import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.AdminUserPageQuery;
import com.courseinsight.server.dto.AdminUserResponse;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.GlobalExceptionHandler;
import com.courseinsight.server.exception.UserRoleConflictException;
import com.courseinsight.server.service.AdminUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminUserController.class)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {AdminUserController.class, GlobalExceptionHandler.class})
class AdminUserControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminUserService adminUserService;

    @Test
    void shouldPageUsers() throws Exception {
        AdminUserResponse user = response(2L, UserRole.STUDENT);
        given(adminUserService.page(any(AdminUserPageQuery.class)))
                .willReturn(new PageResponse<>(1, 10, 1, 1, List.of(user)));

        mockMvc.perform(get("/api/admin/users")
                        .param("keyword", "student")
                        .param("role", "student")
                        .param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].username").value("student_02"))
                .andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist());
    }

    @Test
    void shouldUpdateUserRole() throws Exception {
        given(adminUserService.updateRole(1L, 2L, UserRole.TEACHER))
                .willReturn(response(2L, UserRole.TEACHER));

        mockMvc.perform(patch("/api/admin/users/{userId}/role", 2L)
                        .principal(() -> "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"teacher"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(2))
                .andExpect(jsonPath("$.data.role").value("TEACHER"));

        verify(adminUserService).updateRole(1L, 2L, UserRole.TEACHER);
    }

    @Test
    void shouldRejectInvalidRole() throws Exception {
        mockMvc.perform(patch("/api/admin/users/{userId}/role", 2L)
                        .principal(() -> "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"OWNER"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message")
                        .value("用户角色必须是STUDENT、TEACHER或ADMIN"));

        verifyNoInteractions(adminUserService);
    }

    @Test
    void shouldReturnConflictForSelfDemotion() throws Exception {
        given(adminUserService.updateRole(eq(1L), eq(1L), eq(UserRole.STUDENT)))
                .willThrow(new UserRoleConflictException("不能取消自己的管理员角色"));

        mockMvc.perform(patch("/api/admin/users/{userId}/role", 1L)
                        .principal(() -> "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"STUDENT"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("不能取消自己的管理员角色"));
    }

    private AdminUserResponse response(Long id, UserRole role) {
        return new AdminUserResponse(
                id,
                role == UserRole.ADMIN ? "admin" : "student_02",
                role == UserRole.ADMIN ? "管理员" : "测试学生",
                role.name(),
                1,
                LocalDateTime.of(2026, 8, 3, 10, 0),
                LocalDateTime.of(2026, 8, 3, 10, 0)
        );
    }
}
