package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.CourseDetailResponse;
import com.courseinsight.server.dto.CoursePageQuery;
import com.courseinsight.server.entity.Course;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.CourseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class CourseServiceTests {

    @Mock
    private CourseMapper courseMapper;

    @InjectMocks
    private CourseService courseService;

    @Test
    void shouldGetCourseById() {
        Course course = createCourse();
        given(courseMapper.selectById(1L)).willReturn(course);

        CourseDetailResponse response = courseService.getById(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.code()).isEqualTo("CS101");
        assertThat(response.name()).isEqualTo("Java程序设计");
        assertThat(response.teacherName()).isEqualTo("张老师");
    }

    @Test
    void shouldThrowWhenCourseDoesNotExist() {
        given(courseMapper.selectById(999999L)).willReturn(null);

        assertThatThrownBy(() -> courseService.getById(999999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("课程不存在");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldPageCourses() {
        Page<Course> mapperResult = new Page<>(1, 10, 1);
        mapperResult.setRecords(List.of(createCourse()));
        given(courseMapper.selectPage(any(Page.class), any(Wrapper.class)))
                .willReturn(mapperResult);

        PageResponse<CourseDetailResponse> response = courseService.page(
                new CoursePageQuery(1, 10, "Java")
        );

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.total()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.items()).singleElement()
                .extracting(CourseDetailResponse::code)
                .isEqualTo("CS101");
    }

    private Course createCourse() {
        Course course = new Course();
        course.setId(1L);
        course.setCode("CS101");
        course.setName("Java程序设计");
        course.setTeacherName("张老师");
        course.setDescription("Java基础课程");
        course.setStatus(1);
        course.setCreatedAt(LocalDateTime.of(2026, 7, 31, 10, 0));
        course.setUpdatedAt(LocalDateTime.of(2026, 7, 31, 10, 0));
        return course;
    }
}
