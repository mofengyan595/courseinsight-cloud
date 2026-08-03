package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.AdminUserPageQuery;
import com.courseinsight.server.dto.AdminUserResponse;
import com.courseinsight.server.entity.AppUser;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.exception.UserRoleConflictException;
import com.courseinsight.server.mapper.AppUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AdminUserService {

    private final AppUserMapper appUserMapper;

    public AdminUserService(AppUserMapper appUserMapper) {
        this.appUserMapper = appUserMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> page(AdminUserPageQuery query) {
        LambdaQueryWrapper<AppUser> wrapper = new LambdaQueryWrapper<>();

        if (query.keyword() != null) {
            wrapper.and(condition -> condition
                    .like(AppUser::getUsername, query.keyword())
                    .or()
                    .like(AppUser::getDisplayName, query.keyword()));
        }
        wrapper.eq(query.role() != null, AppUser::getRole, query.role())
                .eq(query.status() != null, AppUser::getStatus, query.status())
                .orderByDesc(AppUser::getCreatedAt)
                .orderByDesc(AppUser::getId);

        Page<AppUser> result = appUserMapper.selectPage(
                new Page<>(query.page(), query.size()),
                wrapper
        );
        List<AdminUserResponse> items = result.getRecords()
                .stream()
                .map(AdminUserResponse::from)
                .toList();

        return new PageResponse<>(
                result.getCurrent(),
                result.getSize(),
                result.getTotal(),
                result.getPages(),
                items
        );
    }

    @Transactional
    public AdminUserResponse updateRole(
            Long currentAdministratorId,
            Long targetUserId,
            UserRole targetRole) {
        AppUser targetUser = appUserMapper.selectById(targetUserId);
        if (targetUser == null) {
            throw new ResourceNotFoundException("用户不存在");
        }

        if (currentAdministratorId.equals(targetUserId)
                && targetRole != UserRole.ADMIN) {
            throw new UserRoleConflictException("不能取消自己的管理员角色");
        }

        if (targetRole.name().equals(targetUser.getRole())) {
            return AdminUserResponse.from(targetUser);
        }

        targetUser.setRole(targetRole.name());
        if (appUserMapper.updateById(targetUser) != 1) {
            throw new ResourceNotFoundException("用户不存在");
        }
        return AdminUserResponse.from(targetUser);
    }
}
