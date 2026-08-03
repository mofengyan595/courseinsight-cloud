package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.courseinsight.server.cache.CourseCacheLookup;
import com.courseinsight.server.cache.CourseDetailCache;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.CourseDetailResponse;
import com.courseinsight.server.dto.CourseCreateRequest;
import com.courseinsight.server.dto.CoursePageQuery;
import com.courseinsight.server.dto.CourseUpdateRequest;
import com.courseinsight.server.entity.Course;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.CourseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CourseServiceTests {

    @Mock
    private CourseMapper courseMapper;

    @Mock
    private CourseDetailCache courseDetailCache;

    @Mock
    private CourseManagementAccessService managementAccessService;

    @InjectMocks
    private CourseService courseService;

    @Test
    void shouldBindCreatedCourseToCurrentUser() {
        given(courseMapper.insert(any(Course.class))).willAnswer(invocation -> {
            Course course = invocation.getArgument(0);
            course.setId(3L);
            return 1;
        });

        Long courseId = courseService.create(
                11L,
                new CourseCreateRequest(
                        "CS103",
                        "Java程序设计",
                        "张老师",
                        "Java基础课程"
                )
        );

        ArgumentCaptor<Course> captor = ArgumentCaptor.forClass(Course.class);
        verify(courseMapper).insert(captor.capture());
        assertThat(courseId).isEqualTo(3L);
        assertThat(captor.getValue().getOwnerUserId()).isEqualTo(11L);
    }

    @Test
    void shouldUpdateCourseAndEvictCache() {
        given(managementAccessService.requireManageableCourse(
                1L,
                11L,
                UserRole.TEACHER
        )).willReturn(createCourse());
        given(courseMapper.updateById(any(Course.class))).willReturn(1);

        Long courseId = courseService.update(
                11L,
                UserRole.TEACHER,
                1L,
                new CourseUpdateRequest(
                        "CS101",
                        "Java高级程序设计",
                        "李老师",
                        "Java进阶课程",
                        1
                )
        );

        assertThat(courseId).isEqualTo(1L);
        verify(courseDetailCache).evict(1L);
    }

    @Test
    void shouldRejectUpdateWhenCourseDoesNotExist() {
        given(managementAccessService.requireManageableCourse(
                999999L,
                11L,
                UserRole.TEACHER
        )).willThrow(new ResourceNotFoundException("课程不存在"));

        assertThatThrownBy(() -> courseService.update(
                11L,
                UserRole.TEACHER,
                999999L,
                new CourseUpdateRequest("CS999", "不存在", "测试教师", null, 1)
        )).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("课程不存在");

        verifyNoInteractions(courseDetailCache);
    }

    @Test
    void shouldGetCourseById() {
        Course course = createCourse();
        given(courseDetailCache.get(1L)).willReturn(CourseCacheLookup.miss());
        given(courseMapper.selectById(1L)).willReturn(course);

        CourseDetailResponse response = courseService.getById(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.code()).isEqualTo("CS101");
        assertThat(response.name()).isEqualTo("Java程序设计");
        assertThat(response.teacherName()).isEqualTo("张老师");
        verify(courseDetailCache).put(1L, response);
    }

    @Test
    void shouldThrowWhenCourseDoesNotExist() {
        given(courseDetailCache.get(999999L)).willReturn(CourseCacheLookup.miss());
        given(courseMapper.selectById(999999L)).willReturn(null);

        assertThatThrownBy(() -> courseService.getById(999999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("课程不存在");
        verify(courseDetailCache).putNotFound(999999L);
    }

    @Test
    void shouldReturnCachedCourseWithoutQueryingDatabase() {
        CourseDetailResponse cachedCourse = CourseDetailResponse.from(createCourse());
        given(courseDetailCache.get(1L)).willReturn(CourseCacheLookup.found(cachedCourse));

        CourseDetailResponse response = courseService.getById(1L);

        assertThat(response).isSameAs(cachedCourse);
        verifyNoInteractions(courseMapper);
    }

    @Test
    void shouldReturnNotFoundFromNullCacheWithoutQueryingDatabase() {
        given(courseDetailCache.get(999999L)).willReturn(CourseCacheLookup.notFound());

        assertThatThrownBy(() -> courseService.getById(999999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("课程不存在");

        verifyNoInteractions(courseMapper);
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
        course.setOwnerUserId(11L);
        course.setDescription("Java基础课程");
        course.setStatus(1);
        course.setCreatedAt(LocalDateTime.of(2026, 7, 31, 10, 0));
        course.setUpdatedAt(LocalDateTime.of(2026, 7, 31, 10, 0));
        return course;
    }
}
