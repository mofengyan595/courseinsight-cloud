package com.courseinsight.server.controller;

import com.courseinsight.server.common.ApiResponse;
import jakarta.validation.constraints.Positive;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.CommentCreateRequest;
import com.courseinsight.server.dto.CommentDetailResponse;
import com.courseinsight.server.dto.CommentPageQuery;
import com.courseinsight.server.service.CommentService;
import com.courseinsight.server.security.CurrentUser;
import org.springframework.security.core.Authentication;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api")
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @PostMapping("/courses/{courseId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Long> create(
            @PathVariable @Positive(message = "ID 必须是正整数") Long courseId,
            Authentication authentication,
            @Valid @RequestBody CommentCreateRequest request) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        return ApiResponse.success(commentService.create(
                courseId,
                currentUser.id(),
                currentUser.role(),
                request
        ));
    }

    @GetMapping("/courses/{courseId}/comments")
    public ApiResponse<PageResponse<CommentDetailResponse>> page(
            @PathVariable @Positive(message = "ID 必须是正整数") Long courseId,
            Authentication authentication,
            @Valid @ModelAttribute CommentPageQuery query) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        return ApiResponse.success(commentService.pageForUser(courseId, query, currentUser.id(), currentUser.role()));
    }

    @GetMapping("/comments/me")
    public ApiResponse<PageResponse<CommentDetailResponse>> myComments(
            Principal principal,
            @Valid @ModelAttribute CommentPageQuery query) {
        return ApiResponse.success(commentService.pageByUser(
                currentUserId(principal),
                query
        ));
    }

    @DeleteMapping("/comments/{commentId}")
    public ApiResponse<Void> delete(
            @PathVariable @Positive(message = "ID 必须是正整数") Long commentId,
            Authentication authentication) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        commentService.delete(commentId, currentUser.id(), currentUser.role());
        return ApiResponse.success(null);
    }

    private Long currentUserId(Principal principal) {
        return Long.valueOf(principal.getName());
    }
}
