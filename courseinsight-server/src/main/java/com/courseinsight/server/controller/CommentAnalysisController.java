package com.courseinsight.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.courseinsight.server.common.ApiResponse;
import jakarta.validation.constraints.Positive;
import com.courseinsight.server.dto.CommentAnalysisResponse;
import com.courseinsight.server.security.CurrentUser;
import com.courseinsight.server.service.CommentAnalysisService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CommentAnalysisController {

    private final CommentAnalysisService service;

    public CommentAnalysisController(CommentAnalysisService service) {
        this.service = service;
    }

    @GetMapping("/api/comments/{commentId}/analysis")
    @Operation(operationId = "getCommentAnalysis", summary = "教师或管理员查询评价分析结果")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "评价或分析结果不存在",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    public ApiResponse<CommentAnalysisResponse> getByCommentId(
            @PathVariable @Positive(message = "ID 必须是正整数") Long commentId, Authentication authentication) {
        CurrentUser user = CurrentUser.from(authentication);
        return ApiResponse.success(service.getByCommentId(commentId, user.id(), user.role()));
    }
}
