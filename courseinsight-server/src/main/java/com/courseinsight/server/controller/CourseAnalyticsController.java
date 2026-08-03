package com.courseinsight.server.controller;

import com.courseinsight.server.common.ApiResponse;
import com.courseinsight.server.dto.CourseAnalyticsSummaryResponse;
import com.courseinsight.server.security.CurrentUser;
import com.courseinsight.server.service.CourseAnalyticsService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/courses/{courseId}/analytics")
public class CourseAnalyticsController {

    private final CourseAnalyticsService courseAnalyticsService;

    public CourseAnalyticsController(CourseAnalyticsService courseAnalyticsService) {
        this.courseAnalyticsService = courseAnalyticsService;
    }

    @GetMapping("/summary")
    public ApiResponse<CourseAnalyticsSummaryResponse> getSummary(
            @PathVariable Long courseId,
            Authentication authentication) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        return ApiResponse.success(courseAnalyticsService.getSummary(
                courseId,
                currentUser.id(),
                currentUser.role()
        ));
    }
}
