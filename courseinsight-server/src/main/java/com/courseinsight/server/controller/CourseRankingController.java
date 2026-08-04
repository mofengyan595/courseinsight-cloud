package com.courseinsight.server.controller;

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
    public ApiResponse<List<CoursePopularityRankingItemResponse>> getPopular(
            @Valid @ModelAttribute CourseRankingQuery query) {
        return ApiResponse.success(rankingService.getPopular(query));
    }
}
