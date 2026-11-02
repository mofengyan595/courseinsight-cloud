package com.courseinsight.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.courseinsight.server.common.ApiResponse;
import jakarta.validation.constraints.Positive;
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
    @Operation(operationId = "getCourseAnalytics", summary = "教师或管理员查询课程分析统计")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "课程不存在",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    public ApiResponse<CourseAnalyticsSummaryResponse> getSummary(
            @PathVariable @Positive(message = "ID 必须是正整数") Long courseId,
            Authentication authentication) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        return ApiResponse.success(courseAnalyticsService.getSummary(
                courseId,
                currentUser.id(),
                currentUser.role()
        ));
    }
}
