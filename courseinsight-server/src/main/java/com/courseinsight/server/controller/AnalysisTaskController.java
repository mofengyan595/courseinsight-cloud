package com.courseinsight.server.controller;

import com.courseinsight.server.common.ApiResponse;
import com.courseinsight.server.dto.AnalysisTaskDetailResponse;
import com.courseinsight.server.service.AnalysisTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/comments/{commentId}/analysis-task")
public class AnalysisTaskController {

    private final AnalysisTaskService analysisTaskService;

    public AnalysisTaskController(AnalysisTaskService analysisTaskService) {
        this.analysisTaskService = analysisTaskService;
    }

    @GetMapping
    public ApiResponse<AnalysisTaskDetailResponse> getByCommentId(
            @PathVariable Long commentId) {
        return ApiResponse.success(analysisTaskService.getByCommentId(commentId));
    }
}
