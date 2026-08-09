package com.courseinsight.server.security;

import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.config.SecurityConfig;
import com.courseinsight.server.controller.AdminUserController;
import com.courseinsight.server.controller.AnalysisBatchController;
import com.courseinsight.server.controller.CommentController;
import com.courseinsight.server.controller.CourseController;
import com.courseinsight.server.controller.CourseAnalyticsController;
import com.courseinsight.server.controller.HealthController;
import com.courseinsight.server.dto.CoursePageQuery;
import com.courseinsight.server.dto.AnalysisBatchCreateResponse;
import com.courseinsight.server.dto.AnalysisBatchRetryResponse;
import com.courseinsight.server.dto.CourseAnalyticsSummaryResponse;
import com.courseinsight.server.entity.AppUser;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.mapper.AppUserMapper;
import com.courseinsight.server.service.AdminUserService;
import com.courseinsight.server.service.AnalysisBatchService;
import com.courseinsight.server.service.AnalysisBatchRecoveryService;
import com.courseinsight.server.service.AnalysisBatchResultService;
import com.courseinsight.server.service.CommentService;
import com.courseinsight.server.service.CourseService;
import com.courseinsight.server.service.CourseAnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        AdminUserController.class,
        AnalysisBatchController.class,
        CourseAnalyticsController.class,
        CourseController.class,
        CommentController.class,
        HealthController.class
})
@ContextConfiguration(classes = {
        AdminUserController.class,
        AnalysisBatchController.class,
        CourseAnalyticsController.class,
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
    private CourseAnalyticsService courseAnalyticsService;

    @MockitoBean
    private CommentService commentService;

    @MockitoBean
    private AdminUserService adminUserService;

    @MockitoBean
    private AnalysisBatchService analysisBatchService;

    @MockitoBean
    private AnalysisBatchResultService analysisBatchResultService;

    @MockitoBean
    private AnalysisBatchRecoveryService analysisBatchRecoveryService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private AppUserMapper appUserMapper;

    private String authoritativeRole;

    @BeforeEach
    void stubAuthoritativeUser() {
        given(appUserMapper.selectById(1L)).willAnswer(ignored -> {
            AppUser user = new AppUser();
            user.setId(1L);
            user.setRole(authoritativeRole);
            user.setStatus(1);
            return user;
        });
    }

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
    void shouldForbidStudentFromViewingCourseAnalytics() throws Exception {
        given(jwtDecoder.decode("student-token"))
                .willReturn(jwt("student-token", "STUDENT"));

        mockMvc.perform(get("/api/courses/{courseId}/analytics/summary", 14L)
                        .header("Authorization", "Bearer student-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verifyNoInteractions(courseAnalyticsService);
    }

    @Test
    void shouldAllowTeacherToViewCourseAnalyticsEndpoint() throws Exception {
        given(jwtDecoder.decode("teacher-token"))
                .willReturn(jwt("teacher-token", "TEACHER"));
        given(courseAnalyticsService.getSummary(14L, 1L, UserRole.TEACHER))
                .willReturn(emptySummary(14L));

        mockMvc.perform(get("/api/courses/{courseId}/analytics/summary", 14L)
                        .header("Authorization", "Bearer teacher-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(courseAnalyticsService).getSummary(14L, 1L, UserRole.TEACHER);
    }

    @Test
    void shouldForbidStudentFromListingUsers() throws Exception {
        given(jwtDecoder.decode("student-token"))
                .willReturn(jwt("student-token", "STUDENT"));

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer student-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verifyNoInteractions(adminUserService);
    }

    @Test
    void shouldAllowAdministratorToListUsers() throws Exception {
        given(jwtDecoder.decode("admin-token"))
                .willReturn(jwt("admin-token", "ADMIN"));
        given(adminUserService.page(any()))
                .willReturn(new PageResponse<>(1, 10, 0, 0, List.of()));

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(adminUserService).page(any());
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
        given(courseService.create(eq(1L), any())).willReturn(99L);

        mockMvc.perform(post("/api/courses")
                        .header("Authorization", "Bearer teacher-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCourseRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(99));

        verify(courseService).create(eq(1L), any());
    }

    @Test
    void shouldForbidStudentFromUploadingAnalysisBatch() throws Exception {
        given(jwtDecoder.decode("student-token"))
                .willReturn(jwt("student-token", "STUDENT"));

        mockMvc.perform(multipart("/api/courses/{courseId}/analysis-batches", 14L)
                        .file(csvFile())
                        .header("Authorization", "Bearer student-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verifyNoInteractions(analysisBatchService);
    }

    @Test
    void shouldAllowTeacherToUploadAnalysisBatch() throws Exception {
        given(jwtDecoder.decode("teacher-token"))
                .willReturn(jwt("teacher-token", "TEACHER"));
        given(analysisBatchService.upload(eq(14L), eq(1L), eq(UserRole.TEACHER), any()))
                .willReturn(new AnalysisBatchCreateResponse(
                        30L,
                        "batch-30",
                        14L,
                        1
                ));

        mockMvc.perform(multipart("/api/courses/{courseId}/analysis-batches", 14L)
                        .file(csvFile())
                        .header("Authorization", "Bearer teacher-token"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.batchId").value(30));
    }

    @Test
    void shouldForbidStudentFromViewingAnalysisBatchResults() throws Exception {
        given(jwtDecoder.decode("student-token"))
                .willReturn(jwt("student-token", "STUDENT"));

        mockMvc.perform(get("/api/analysis-batches/{batchId}/results", 30L)
                        .header("Authorization", "Bearer student-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verifyNoInteractions(analysisBatchResultService);
    }

    @Test
    void shouldForbidStudentFromExportingAnalysisBatchResults() throws Exception {
        given(jwtDecoder.decode("student-token"))
                .willReturn(jwt("student-token", "STUDENT"));

        mockMvc.perform(get("/api/analysis-batches/{batchId}/export", 30L)
                        .header("Authorization", "Bearer student-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verifyNoInteractions(analysisBatchResultService);
    }

    @Test
    void shouldForbidStudentFromRetryingAnalysisBatch() throws Exception {
        given(jwtDecoder.decode("student-token"))
                .willReturn(jwt("student-token", "STUDENT"));

        mockMvc.perform(post("/api/analysis-batches/{batchId}/retry-failed", 30L)
                        .header("Authorization", "Bearer student-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verifyNoInteractions(analysisBatchRecoveryService);
    }

    @Test
    void shouldAllowTeacherToRetryAnalysisBatch() throws Exception {
        given(jwtDecoder.decode("teacher-token"))
                .willReturn(jwt("teacher-token", "TEACHER"));
        given(analysisBatchRecoveryService.retryDeadLettered(
                30L,
                1L,
                UserRole.TEACHER
        )).willReturn(new AnalysisBatchRetryResponse(
                30L,
                2,
                LocalDateTime.of(2026, 8, 8, 11, 0)
        ));

        mockMvc.perform(post("/api/analysis-batches/{batchId}/retry-failed", 30L)
                        .header("Authorization", "Bearer teacher-token"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.requeuedCount").value(2));
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
        authoritativeRole = role;

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

    private MockMultipartFile csvFile() {
        return new MockMultipartFile(
                "file",
                "comments.csv",
                "text/csv",
                "content,rating\nGood course,5\n".getBytes()
        );
    }

    private CourseAnalyticsSummaryResponse emptySummary(Long courseId) {
        return new CourseAnalyticsSummaryResponse(
                courseId,
                0,
                new BigDecimal("0.00"),
                new CourseAnalyticsSummaryResponse.TaskSummary(
                        0, 0, 0, 0, 0, new BigDecimal("0.00")
                ),
                new CourseAnalyticsSummaryResponse.SentimentSummary(
                        0,
                        0,
                        0,
                        0,
                        new BigDecimal("0.00"),
                        new BigDecimal("0.00"),
                        new BigDecimal("0.00")
                ),
                new CourseAnalyticsSummaryResponse.RiskSummary(0, 0, 0, 0)
        );
    }
}
