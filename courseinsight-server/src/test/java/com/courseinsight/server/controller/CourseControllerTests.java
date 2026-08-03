package com.courseinsight.server.controller;

import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.CourseDetailResponse;
import com.courseinsight.server.dto.CoursePageQuery;
import com.courseinsight.server.exception.GlobalExceptionHandler;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.service.CourseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CourseController.class)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {CourseController.class, GlobalExceptionHandler.class})
class CourseControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CourseService courseService;

    @Test
    void shouldCreateCourse() throws Exception {
        given(courseService.create(eq(11L), any())).willReturn(3L);

        mockMvc.perform(post("/api/courses")
                        .principal(authentication(11L, UserRole.TEACHER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "CS103",
                                  "name": "Java程序设计",
                                  "teacherName": "张老师",
                                  "description": "Java基础课程"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").value(3));
    }

    @Test
    void shouldUpdateCourse() throws Exception {
        given(courseService.update(
                eq(11L),
                eq(UserRole.TEACHER),
                eq(1L),
                any()
        )).willReturn(1L);

        mockMvc.perform(put("/api/courses/{id}", 1L)
                        .principal(authentication(11L, UserRole.TEACHER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "CS101",
                                  "name": "Java高级程序设计",
                                  "teacherName": "李老师",
                                  "description": "Java进阶课程",
                                  "status": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(1));
    }

    @Test
    void shouldRejectInvalidCourseStatusOnUpdate() throws Exception {
        mockMvc.perform(put("/api/courses/{id}", 1L)
                        .principal(authentication(11L, UserRole.TEACHER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "CS101",
                                  "name": "Java高级程序设计",
                                  "teacherName": "李老师",
                                  "description": "Java进阶课程",
                                  "status": 2
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(courseService);
    }

    @Test
    void shouldRejectBlankCourseName() throws Exception {
        mockMvc.perform(post("/api/courses")
                        .principal(authentication(11L, UserRole.TEACHER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "CS104",
                                  "name": "",
                                  "teacherName": "李老师",
                                  "description": "参数校验测试"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("课程名称不能为空"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(courseService);
    }

    @Test
    void shouldReturnConflictForDuplicateCourseCode() throws Exception {
        given(courseService.create(eq(11L), any()))
                .willThrow(new DuplicateKeyException("duplicate course code"));

        mockMvc.perform(post("/api/courses")
                        .principal(authentication(11L, UserRole.TEACHER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "CS101",
                                  "name": "重复课程",
                                  "teacherName": "王老师",
                                  "description": "测试课程代码唯一约束"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("课程代码已存在"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void shouldReturnForbiddenWhenTeacherUpdatesAnotherCourse() throws Exception {
        given(courseService.update(
                eq(12L),
                eq(UserRole.TEACHER),
                eq(1L),
                any()
        )).willThrow(new CourseAccessDeniedException("无权管理其他教师的课程"));

        mockMvc.perform(put("/api/courses/{id}", 1L)
                        .principal(authentication(12L, UserRole.TEACHER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "CS101",
                                  "name": "Java高级程序设计",
                                  "teacherName": "李老师",
                                  "description": "Java进阶课程",
                                  "status": 1
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权管理其他教师的课程"));
    }

    @Test
    void shouldGetCourseById() throws Exception {
        CourseDetailResponse response = new CourseDetailResponse(
                1L,
                "CS101",
                "Java程序设计",
                "张老师",
                "Java基础课程",
                1,
                LocalDateTime.of(2026, 7, 31, 10, 0),
                LocalDateTime.of(2026, 7, 31, 10, 0)
        );
        given(courseService.getById(1L)).willReturn(response);

        mockMvc.perform(get("/api/courses/{id}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.code").value("CS101"))
                .andExpect(jsonPath("$.data.name").value("Java程序设计"))
                .andExpect(jsonPath("$.data.teacherName").value("张老师"))
                .andExpect(jsonPath("$.data.status").value(1));
    }

    @Test
    void shouldReturnNotFoundForMissingCourse() throws Exception {
        given(courseService.getById(999999L))
                .willThrow(new ResourceNotFoundException("课程不存在"));

        mockMvc.perform(get("/api/courses/{id}", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("课程不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void shouldListCourses() throws Exception {
        CourseDetailResponse course = new CourseDetailResponse(
                1L,
                "CS101",
                "Java程序设计",
                "张老师",
                "Java基础课程",
                1,
                LocalDateTime.of(2026, 7, 31, 10, 0),
                LocalDateTime.of(2026, 7, 31, 10, 0)
        );
        given(courseService.page(any(CoursePageQuery.class)))
                .willReturn(new PageResponse<>(1, 10, 1, 1, List.of(course)));

        mockMvc.perform(get("/api/courses")
                        .param("page", "1")
                        .param("size", "10")
                        .param("keyword", "Java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.items[0].code").value("CS101"));
    }

    @Test
    void shouldRejectInvalidPageParameters() throws Exception {
        mockMvc.perform(get("/api/courses")
                        .param("page", "0")
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(courseService);
    }

    private Authentication authentication(Long userId, UserRole role) {
        return UsernamePasswordAuthenticationToken.authenticated(
                userId.toString(),
                "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        );
    }
}
