package com.courseinsight.server.controller;

import com.courseinsight.server.common.ApiResponse;
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
    public ApiResponse<Long> create(
            Authentication authentication,
            @Valid @RequestBody CourseCreateRequest request) {
        CurrentUser currentUser = CurrentUser.from(authentication);
        Long courseId = courseService.create(currentUser.id(), request);
        return ApiResponse.success(courseId);
    }

    @PutMapping("/{id}")
    public ApiResponse<Long> update(
            @PathVariable Long id,
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

    @GetMapping("/{id}")
    public ApiResponse<CourseDetailResponse> getById(@PathVariable Long id) {
        return ApiResponse.success(courseService.getById(id));
    }

    @GetMapping
    public ApiResponse<PageResponse<CourseDetailResponse>> page(
            @Valid @ModelAttribute CoursePageQuery query) {
        return ApiResponse.success(courseService.page(query));
    }
}
