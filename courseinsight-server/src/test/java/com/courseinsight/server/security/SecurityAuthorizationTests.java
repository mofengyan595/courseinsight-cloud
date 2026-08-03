package com.courseinsight.server.security;

import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.config.SecurityConfig;
import com.courseinsight.server.controller.CommentController;
import com.courseinsight.server.controller.CourseController;
import com.courseinsight.server.controller.HealthController;
import com.courseinsight.server.dto.CoursePageQuery;
import com.courseinsight.server.service.CommentService;
import com.courseinsight.server.service.CourseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        CourseController.class,
        CommentController.class,
        HealthController.class
})
@ContextConfiguration(classes = {
        CourseController.class,
        CommentController.class,
        HealthController.class,
        SecurityConfig.class
})
class SecurityAuthorizationTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CourseService courseService;

    @MockitoBean
    private CommentService commentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void shouldAllowPublicHealthEndpoint() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void shouldRejectCourseRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/courses"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录或令牌无效"));

        verifyNoInteractions(courseService);
    }

    @Test
    void shouldRejectInvalidToken() throws Exception {
        given(jwtDecoder.decode("invalid-token"))
                .willThrow(new BadJwtException("invalid token"));

        mockMvc.perform(get("/api/courses")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录或令牌无效"));
    }

    @Test
    void shouldAllowStudentToListCourses() throws Exception {
        given(jwtDecoder.decode("student-token"))
                .willReturn(jwt("student-token", "STUDENT"));
        given(courseService.page(any(CoursePageQuery.class)))
                .willReturn(new PageResponse<>(1, 10, 0, 0, List.of()));

        mockMvc.perform(get("/api/courses")
                        .header("Authorization", "Bearer student-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(courseService).page(any(CoursePageQuery.class));
    }

    @Test
    void shouldForbidStudentFromCreatingCourse() throws Exception {
        given(jwtDecoder.decode("student-token"))
                .willReturn(jwt("student-token", "STUDENT"));

        mockMvc.perform(post("/api/courses")
                        .header("Authorization", "Bearer student-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCourseRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("权限不足"));

        verifyNoInteractions(courseService);
    }

    @Test
    void shouldAllowTeacherToCreateCourse() throws Exception {
        given(jwtDecoder.decode("teacher-token"))
                .willReturn(jwt("teacher-token", "TEACHER"));
        given(courseService.create(any())).willReturn(99L);

        mockMvc.perform(post("/api/courses")
                        .header("Authorization", "Bearer teacher-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCourseRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(99));
    }

    @Test
    void shouldBindStudentCommentToJwtSubject() throws Exception {
        given(jwtDecoder.decode("student-token"))
                .willReturn(jwt("student-token", "STUDENT"));
        given(commentService.create(eq(1L), eq(1L), any())).willReturn(10L);

        mockMvc.perform(post("/api/courses/{courseId}/comments", 1L)
                        .header("Authorization", "Bearer student-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "课程讲解清晰",
                                  "rating": 5
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data").value(10));

        verify(commentService).create(eq(1L), eq(1L), any());
    }

    @Test
    void shouldRejectDeletingCommentWithoutToken() throws Exception {
        mockMvc.perform(delete("/api/comments/{commentId}", 10L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(commentService);
    }

    @Test
    void shouldBindCommentDeletionToJwtSubject() throws Exception {
        given(jwtDecoder.decode("student-token"))
                .willReturn(jwt("student-token", "STUDENT"));

        mockMvc.perform(delete("/api/comments/{commentId}", 10L)
                        .header("Authorization", "Bearer student-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(commentService).delete(10L, 1L);
    }

    private Jwt jwt(String tokenValue, String role) {
        Instant issuedAt = Instant.now();
        return new Jwt(
                tokenValue,
                issuedAt,
                issuedAt.plusSeconds(3600),
                Map.of("alg", "HS256"),
                Map.of(
                        "iss", "https://courseinsight.local",
                        "sub", "1",
                        "username", role.toLowerCase() + "_01",
                        "role", role
                )
        );
    }

    private String validCourseRequest() {
        return """
                {
                  "code": "SEC101",
                  "name": "Application Security",
                  "teacherName": "Test Teacher",
                  "description": "Security authorization test"
                }
                """;
    }
}
