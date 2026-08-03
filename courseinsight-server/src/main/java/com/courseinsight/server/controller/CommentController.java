package com.courseinsight.server.controller;

import com.courseinsight.server.common.ApiResponse;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.CommentCreateRequest;
import com.courseinsight.server.dto.CommentDetailResponse;
import com.courseinsight.server.dto.CommentPageQuery;
import com.courseinsight.server.service.CommentService;
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
            @PathVariable Long courseId,
            Principal principal,
            @Valid @RequestBody CommentCreateRequest request) {
        return ApiResponse.success(commentService.create(
                courseId,
                currentUserId(principal),
                request
        ));
    }

    @GetMapping("/courses/{courseId}/comments")
    public ApiResponse<PageResponse<CommentDetailResponse>> page(
            @PathVariable Long courseId,
            @Valid @ModelAttribute CommentPageQuery query) {
        return ApiResponse.success(commentService.page(courseId, query));
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
            @PathVariable Long commentId,
            Principal principal) {
        commentService.delete(commentId, currentUserId(principal));
        return ApiResponse.success(null);
    }

    private Long currentUserId(Principal principal) {
        return Long.valueOf(principal.getName());
    }
}
