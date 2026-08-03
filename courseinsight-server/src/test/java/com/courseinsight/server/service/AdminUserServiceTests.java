package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.AdminUserPageQuery;
import com.courseinsight.server.dto.AdminUserResponse;
import com.courseinsight.server.entity.AppUser;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.exception.UserRoleConflictException;
import com.courseinsight.server.mapper.AppUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTests {

    @Mock
    private AppUserMapper appUserMapper;

    private AdminUserService adminUserService;

    @BeforeEach
    void setUp() {
        adminUserService = new AdminUserService(appUserMapper);
    }

    @Test
    void shouldPageUsersWithoutExposingPasswordHash() {
        AppUser student = user(2L, UserRole.STUDENT);
        Page<AppUser> mapperResult = new Page<>(1, 10, 1);
        mapperResult.setRecords(List.of(student));
        mapperResult.setTotal(1);
        given(appUserMapper.selectPage(
                org.mockito.ArgumentMatchers.<Page<AppUser>>any(),
                org.mockito.ArgumentMatchers.<Wrapper<AppUser>>any()
        )).willReturn(mapperResult);

        PageResponse<AdminUserResponse> response = adminUserService.page(
                new AdminUserPageQuery(1, 10, "student", "student", 1)
        );

        assertThat(response.total()).isEqualTo(1);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(2L);
            assertThat(item.username()).isEqualTo("student_02");
            assertThat(item.role()).isEqualTo(UserRole.STUDENT.name());
        });
    }

    @Test
    void shouldPromoteStudentToTeacher() {
        AppUser student = user(2L, UserRole.STUDENT);
        given(appUserMapper.selectById(2L)).willReturn(student);
        given(appUserMapper.updateById(student)).willReturn(1);

        AdminUserResponse response = adminUserService.updateRole(
                1L,
                2L,
                UserRole.TEACHER
        );

        assertThat(student.getRole()).isEqualTo(UserRole.TEACHER.name());
        assertThat(response.role()).isEqualTo(UserRole.TEACHER.name());
        verify(appUserMapper).updateById(student);
    }

    @Test
    void shouldRejectAdministratorSelfDemotion() {
        AppUser administrator = user(1L, UserRole.ADMIN);
        given(appUserMapper.selectById(1L)).willReturn(administrator);

        assertThatThrownBy(() -> adminUserService.updateRole(
                1L,
                1L,
                UserRole.STUDENT
        )).isInstanceOf(UserRoleConflictException.class)
                .hasMessage("不能取消自己的管理员角色");

        verify(appUserMapper, never()).updateById(any(AppUser.class));
    }

    @Test
    void shouldReturnNotFoundWhenUpdatingMissingUser() {
        given(appUserMapper.selectById(999L)).willReturn(null);

        assertThatThrownBy(() -> adminUserService.updateRole(
                1L,
                999L,
                UserRole.TEACHER
        )).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("用户不存在");
    }

    private AppUser user(Long id, UserRole role) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setUsername(role == UserRole.ADMIN ? "admin" : "student_02");
        user.setPasswordHash("should-never-be-returned");
        user.setDisplayName(role == UserRole.ADMIN ? "管理员" : "测试学生");
        user.setRole(role.name());
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.of(2026, 8, 3, 10, 0));
        user.setUpdatedAt(LocalDateTime.of(2026, 8, 3, 10, 0));
        return user;
    }
}
