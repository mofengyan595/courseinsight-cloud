package com.courseinsight.server.controller;

import com.courseinsight.server.common.ApiResponse;
import com.courseinsight.server.dto.AnalysisExecutionResponse;
import com.courseinsight.server.service.AnalysisExecutionService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analysis-tasks")
public class AnalysisExecutionController {

    private final AnalysisExecutionService analysisExecutionService;

    public AnalysisExecutionController(AnalysisExecutionService analysisExecutionService) {
        this.analysisExecutionService = analysisExecutionService;
    }

    @PostMapping("/{taskId}/execute")
    public ApiResponse<AnalysisExecutionResponse> execute(@PathVariable Long taskId) {
        return ApiResponse.success(analysisExecutionService.execute(taskId));
    }
}
