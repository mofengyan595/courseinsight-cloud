package com.courseinsight.server.service;

import com.courseinsight.server.entity.Course;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.CourseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class CourseManagementAccessServiceTests {

    @Mock
    private CourseMapper courseMapper;

    private CourseManagementAccessService accessService;

    @BeforeEach
    void setUp() {
        accessService = new CourseManagementAccessService(courseMapper);
    }

    @Test
    void shouldAllowOwningTeacher() {
        Course course = course(11L);
        given(courseMapper.selectById(1L)).willReturn(course);

        Course result = accessService.requireManageableCourse(
                1L,
                11L,
                UserRole.TEACHER
        );

        assertThat(result).isSameAs(course);
    }

    @Test
    void shouldRejectAnotherTeacher() {
        given(courseMapper.selectById(1L)).willReturn(course(11L));

        assertThatThrownBy(() -> accessService.requireManageableCourse(
                1L,
                12L,
                UserRole.TEACHER
        )).isInstanceOf(CourseAccessDeniedException.class)
                .hasMessage("无权管理其他教师的课程");
    }

    @Test
    void shouldRejectStudentEvenWhenIdsMatch() {
        given(courseMapper.selectById(1L)).willReturn(course(11L));

        assertThatThrownBy(() -> accessService.requireManageableCourse(
                1L,
                11L,
                UserRole.STUDENT
        )).isInstanceOf(CourseAccessDeniedException.class);
    }

    @Test
    void shouldAllowAdministratorToManageLegacyCourseWithoutOwner() {
        Course legacyCourse = course(null);
        given(courseMapper.selectById(1L)).willReturn(legacyCourse);

        Course result = accessService.requireManageableCourse(
                1L,
                15L,
                UserRole.ADMIN
        );

        assertThat(result).isSameAs(legacyCourse);
    }

    @Test
    void shouldReturnNotFoundForMissingCourse() {
        given(courseMapper.selectById(999L)).willReturn(null);

        assertThatThrownBy(() -> accessService.requireManageableCourse(
                999L,
                15L,
                UserRole.ADMIN
        )).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("课程不存在");
    }

    private Course course(Long ownerUserId) {
        Course course = new Course();
        course.setId(1L);
        course.setOwnerUserId(ownerUserId);
        return course;
    }
}
