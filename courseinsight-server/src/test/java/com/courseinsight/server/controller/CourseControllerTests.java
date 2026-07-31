package com.courseinsight.server.controller;

import com.courseinsight.server.exception.GlobalExceptionHandler;
import com.courseinsight.server.service.CourseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CourseController.class)
@ContextConfiguration(classes = {CourseController.class, GlobalExceptionHandler.class})
class CourseControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CourseService courseService;

    @Test
    void shouldCreateCourse() throws Exception {
        given(courseService.create(any())).willReturn(3L);

        mockMvc.perform(post("/api/courses")
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
    void shouldRejectBlankCourseName() throws Exception {
        mockMvc.perform(post("/api/courses")
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
        given(courseService.create(any()))
                .willThrow(new DuplicateKeyException("duplicate course code"));

        mockMvc.perform(post("/api/courses")
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
}
