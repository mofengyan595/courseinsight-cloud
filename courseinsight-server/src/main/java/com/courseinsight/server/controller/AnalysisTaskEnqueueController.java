package com.courseinsight.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.courseinsight.server.common.ApiResponse;
import jakarta.validation.constraints.Positive;
import com.courseinsight.server.dto.AnalysisTaskEnqueueResponse;
import com.courseinsight.server.security.CurrentUser;
import com.courseinsight.server.service.AnalysisTaskEnqueueService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
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
    @Operation(operationId = "enqueueAnalysisTask", summary = "管理员将分析任务加入队列")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "任务不存在",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "任务状态冲突",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "操作过于频繁",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    public ApiResponse<AnalysisTaskEnqueueResponse> enqueue(
            @PathVariable @Positive(message = "ID 必须是正整数") Long taskId,
            Authentication authentication) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        return ApiResponse.success(enqueueService.enqueue(
                taskId,
                currentUser.id(),
                currentUser.role()
        ));
    }
}
