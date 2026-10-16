package com.courseinsight.server.service;

import com.courseinsight.server.entity.Course;
import com.courseinsight.server.entity.AppUser;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.CourseMapper;
import com.courseinsight.server.mapper.AppUserMapper;
import com.courseinsight.server.security.WriteAccessPolicy;
import org.springframework.stereotype.Service;

@Service
public class CourseManagementAccessService {

    private final CourseMapper courseMapper;
    private final AppUserMapper userMapper;

    public CourseManagementAccessService(CourseMapper courseMapper, AppUserMapper userMapper) {
        this.courseMapper = courseMapper;
        this.userMapper = userMapper;
    }

    public Course requireManageableCourse(
            Long courseId,
            Long currentUserId,
            UserRole currentRole) {
        WriteAccessPolicy.requireAdmin(currentRole);
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new ResourceNotFoundException("课程不存在");
        }

        return course;
    }

    public void assertCanAnalyze(Long courseId, Long currentUserId, UserRole currentRole) {
        if (currentRole != UserRole.TEACHER && currentRole != UserRole.ADMIN) {
            throw new CourseAccessDeniedException("仅教师或管理员可以查看评价分析");
        }
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new ResourceNotFoundException("课程不存在");
        }
        if (currentRole == UserRole.TEACHER && !currentUserId.equals(course.getOwnerUserId())) {
            throw new CourseAccessDeniedException("无权查看其他教师的课程");
        }
    }

    public Long requireTeacherUserId(Long teacherUserId) {
        AppUser teacher = userMapper.selectById(teacherUserId);
        if (teacher == null || !UserRole.TEACHER.name().equals(teacher.getRole())
                || !Integer.valueOf(1).equals(teacher.getStatus())) {
            throw new CourseAccessDeniedException("请选择有效且已启用的教师账号");
        }
        return teacher.getId();
    }

    public void assertCanManage(
            Long courseId,
            Long currentUserId,
            UserRole currentRole) {
        requireManageableCourse(courseId, currentUserId, currentRole);
    }
}
