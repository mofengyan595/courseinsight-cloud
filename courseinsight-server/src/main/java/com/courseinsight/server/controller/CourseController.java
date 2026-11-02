package com.courseinsight.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.courseinsight.server.common.ApiResponse;
import jakarta.validation.constraints.Positive;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.CourseCreateRequest;
import com.courseinsight.server.dto.CourseDetailResponse;
import com.courseinsight.server.dto.CoursePageQuery;
import com.courseinsight.server.dto.CourseUpdateRequest;
import com.courseinsight.server.service.CourseService;
import com.courseinsight.server.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createCourse", summary = "管理员创建课程")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "课程代码已存在",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    public ApiResponse<Long> create(
            Authentication authentication,
            @Valid @RequestBody CourseCreateRequest request) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        Long courseId = courseService.create(currentUser.id(), currentUser.role(), request);
        return ApiResponse.success(courseId);
    }

    @PutMapping("/{id}")
    @Operation(operationId = "updateCourse", summary = "管理员更新课程")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "课程不存在",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "课程代码已存在",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    public ApiResponse<Long> update(
            @PathVariable @Positive(message = "ID 必须是正整数") Long id,
            Authentication authentication,
            @Valid @RequestBody CourseUpdateRequest request) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        return ApiResponse.success(courseService.update(
                currentUser.id(),
                currentUser.role(),
                id,
                request
        ));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteCourse", summary = "管理员删除空课程")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "课程不存在",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "课程有关联评价（包括已软删除评价）、分析批次、任务或结果，拒绝删除",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "空课程已删除，无响应体", content = @Content)
    public void delete(
            @PathVariable @Positive(message = "ID 必须是正整数") Long id,
            Authentication authentication) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        courseService.delete(currentUser.id(), currentUser.role(), id);
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getCourse", summary = "查询可访问的课程")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "课程不存在",
            content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiError")))
    public ApiResponse<CourseDetailResponse> getById(@PathVariable @Positive(message = "ID 必须是正整数") Long id,
            Authentication authentication) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        return ApiResponse.success(courseService.getForUser(id, currentUser.id(), currentUser.role()));
    }

    @GetMapping
    @Operation(operationId = "listCourses", summary = "分页查询可访问的课程")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "成功",
            useReturnTypeSchema = true, content = @Content(mediaType = "application/json"))
    public ApiResponse<PageResponse<CourseDetailResponse>> page(
            Authentication authentication,
            @Valid @ParameterObject @ModelAttribute CoursePageQuery query) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        return ApiResponse.success(courseService.pageForUser(query, currentUser.id(), currentUser.role()));
    }
}
