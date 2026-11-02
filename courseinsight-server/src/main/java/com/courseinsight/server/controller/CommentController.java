package com.courseinsight.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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
    @Operation(operationId = "createComment", summary = "学生或管理员提交课程评价")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "课程不存在",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "已经评价过该课程",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "提交评价过于频繁",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
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
    @Operation(operationId = "listCourseComments", summary = "分页查询可访问的课程评价")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "课程不存在",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    public ApiResponse<PageResponse<CommentDetailResponse>> page(
            @PathVariable @Positive(message = "ID 必须是正整数") Long courseId,
            Authentication authentication,
            @Valid @ParameterObject @ModelAttribute CommentPageQuery query) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        return ApiResponse.success(commentService.pageForUser(courseId, query, currentUser.id(), currentUser.role()));
    }

    @GetMapping("/comments/me")
    @Operation(operationId = "listMyComments", summary = "分页查询本人评价")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    public ApiResponse<PageResponse<CommentDetailResponse>> myComments(
            Principal principal,
            @Valid @ParameterObject @ModelAttribute CommentPageQuery query) {
        return ApiResponse.success(commentService.pageByUser(
                currentUserId(principal),
                query
        ));
    }

    @DeleteMapping("/comments/{commentId}")
    @Operation(operationId = "deleteComment", summary = "学生删除本人评价或管理员删除评价")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "评价不存在或不可删除",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
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
