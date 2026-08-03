package com.courseinsight.server.service;

import com.courseinsight.server.entity.Course;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.CourseMapper;
import org.springframework.stereotype.Service;

@Service
public class CourseManagementAccessService {

    private final CourseMapper courseMapper;

    public CourseManagementAccessService(CourseMapper courseMapper) {
        this.courseMapper = courseMapper;
    }

    public Course requireManageableCourse(
            Long courseId,
            Long currentUserId,
            UserRole currentRole) {
        Course course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new ResourceNotFoundException("课程不存在");
        }

        if (currentRole == UserRole.ADMIN) {
            return course;
        }
        if (currentRole != UserRole.TEACHER
                || !currentUserId.equals(course.getOwnerUserId())) {
            throw new CourseAccessDeniedException("无权管理其他教师的课程");
        }
        return course;
    }

    public void assertCanManage(
            Long courseId,
            Long currentUserId,
            UserRole currentRole) {
        requireManageableCourse(courseId, currentUserId, currentRole);
    }
}
