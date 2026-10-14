package com.courseinsight.server.controller;

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
    public ApiResponse<CommentAnalysisResponse> getByCommentId(
            @PathVariable @Positive(message = "ID 必须是正整数") Long commentId, Authentication authentication) {
        CurrentUser user = CurrentUser.from(authentication);
        return ApiResponse.success(service.getByCommentId(commentId, user.id(), user.role()));
    }
}
