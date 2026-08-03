package com.courseinsight.server.controller;

import com.courseinsight.server.dto.CourseAnalyticsSummaryResponse;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.exception.GlobalExceptionHandler;
import com.courseinsight.server.service.CourseAnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CourseAnalyticsController.class)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {
        CourseAnalyticsController.class,
        GlobalExceptionHandler.class
})
class CourseAnalyticsControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CourseAnalyticsService courseAnalyticsService;

    @Test
    void shouldReturnCourseAnalyticsSummary() throws Exception {
        given(courseAnalyticsService.getSummary(14L, 11L, UserRole.TEACHER))
                .willReturn(summary());

        mockMvc.perform(get("/api/courses/{courseId}/analytics/summary", 14L)
                        .principal(teacherAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.courseId").value(14))
                .andExpect(jsonPath("$.data.totalComments").value(6))
                .andExpect(jsonPath("$.data.averageRating").value(3.0))
                .andExpect(jsonPath("$.data.tasks.success").value(3))
                .andExpect(jsonPath("$.data.tasks.completionPercentage").value(50.0))
                .andExpect(jsonPath("$.data.sentiments.positivePercentage").value(33.33))
                .andExpect(jsonPath("$.data.risks.unclassified").value(1));
    }

    @Test
    void shouldReturnForbiddenForAnotherTeacher() throws Exception {
        given(courseAnalyticsService.getSummary(14L, 12L, UserRole.TEACHER))
                .willThrow(new CourseAccessDeniedException("无权管理其他教师的课程"));

        mockMvc.perform(get("/api/courses/{courseId}/analytics/summary", 14L)
                        .principal(authentication(12L, UserRole.TEACHER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权管理其他教师的课程"));
    }

    private CourseAnalyticsSummaryResponse summary() {
        return new CourseAnalyticsSummaryResponse(
                14L,
                6,
                new BigDecimal("3.00"),
                new CourseAnalyticsSummaryResponse.TaskSummary(
                        6, 1, 1, 3, 1, new BigDecimal("50.00")
                ),
                new CourseAnalyticsSummaryResponse.SentimentSummary(
                        3,
                        1,
                        1,
                        1,
                        new BigDecimal("33.33"),
                        new BigDecimal("33.33"),
                        new BigDecimal("33.33")
                ),
                new CourseAnalyticsSummaryResponse.RiskSummary(1, 1, 0, 1)
        );
    }

    private Authentication teacherAuthentication() {
        return authentication(11L, UserRole.TEACHER);
    }

    private Authentication authentication(Long userId, UserRole role) {
        return UsernamePasswordAuthenticationToken.authenticated(
                userId.toString(),
                "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        );
    }
}
