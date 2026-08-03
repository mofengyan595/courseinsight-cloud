package com.courseinsight.server.controller;

import com.courseinsight.server.common.ApiResponse;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.CourseCreateRequest;
import com.courseinsight.server.dto.CourseDetailResponse;
import com.courseinsight.server.dto.CoursePageQuery;
import com.courseinsight.server.dto.CourseUpdateRequest;
import com.courseinsight.server.service.CourseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
            @Valid @RequestBody CourseCreateRequest request) {
        Long courseId = courseService.create(request);
        return ApiResponse.success(courseId);
    }

    @PutMapping("/{id}")
    public ApiResponse<Long> update(
            @PathVariable Long id,
            @Valid @RequestBody CourseUpdateRequest request) {
        return ApiResponse.success(courseService.update(id, request));
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
