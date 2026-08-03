package com.courseinsight.server.controller;

import com.courseinsight.server.common.ApiResponse;
import com.courseinsight.server.dto.AnalysisTaskEnqueueResponse;
import com.courseinsight.server.service.AnalysisTaskEnqueueService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analysis-tasks")
public class AnalysisTaskEnqueueController {

    private final AnalysisTaskEnqueueService enqueueService;

    public AnalysisTaskEnqueueController(AnalysisTaskEnqueueService enqueueService) {
        this.enqueueService = enqueueService;
    }

    @PostMapping("/{taskId}/enqueue")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AnalysisTaskEnqueueResponse> enqueue(@PathVariable Long taskId) {
        return ApiResponse.success(enqueueService.enqueue(taskId));
    }
}
