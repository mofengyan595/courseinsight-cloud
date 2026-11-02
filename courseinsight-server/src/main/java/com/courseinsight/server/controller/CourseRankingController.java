package com.courseinsight.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import org.springdoc.core.annotations.ParameterObject;
import com.courseinsight.server.common.ApiResponse;
import com.courseinsight.server.dto.CoursePopularityRankingItemResponse;
import com.courseinsight.server.dto.CourseRankingQuery;
import com.courseinsight.server.service.CoursePopularityRankingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/course-rankings")
public class CourseRankingController {

    private final CoursePopularityRankingService rankingService;

    public CourseRankingController(CoursePopularityRankingService rankingService) {
        this.rankingService = rankingService;
    }

    @GetMapping("/popular")
    @Operation(operationId = "listPopularCourses", summary = "学生或管理员查询热门课程")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    public ApiResponse<List<CoursePopularityRankingItemResponse>> getPopular(
            @Valid @ParameterObject @ModelAttribute CourseRankingQuery query) {
        return ApiResponse.success(rankingService.getPopular(query));
    }
}
