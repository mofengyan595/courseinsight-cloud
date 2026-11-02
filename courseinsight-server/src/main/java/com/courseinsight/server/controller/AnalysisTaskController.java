package com.courseinsight.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.courseinsight.server.common.ApiResponse;
import jakarta.validation.constraints.Positive;
import com.courseinsight.server.dto.AnalysisTaskDetailResponse;
import com.courseinsight.server.security.CurrentUser;
import com.courseinsight.server.service.AnalysisTaskService;
import org.springframework.security.core.Authentication;
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
    @Operation(operationId = "getCommentAnalysisTask", summary = "查询可访问评价的分析任务")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "评价或任务不存在",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    public ApiResponse<AnalysisTaskDetailResponse> getByCommentId(
            @PathVariable @Positive(message = "ID 必须是正整数") Long commentId,
            Authentication authentication) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        return ApiResponse.success(analysisTaskService.getByCommentId(
                commentId,
                currentUser.id(),
                currentUser.role()
        ));
    }
}
