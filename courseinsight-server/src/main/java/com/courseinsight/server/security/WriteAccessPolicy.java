package com.courseinsight.server.security;

import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.CourseAccessDeniedException;

public final class WriteAccessPolicy {
    private WriteAccessPolicy() {
    }

    public static void requireAdmin(UserRole role) {
        if (role != UserRole.ADMIN) {
            throw new CourseAccessDeniedException("仅管理员可修改课程或分析任务");
        }
    }

    public static void requireCommentWrite(UserRole role) {
        if (role != UserRole.ADMIN && role != UserRole.STUDENT) {
            throw new CourseAccessDeniedException("教师仅可查看评价与分析");
        }
    }
}
