package com.courseinsight.server.controller;

import com.courseinsight.server.dto.CourseCreateRequest;
import com.courseinsight.server.service.CourseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Long> create(
            @Valid @RequestBody CourseCreateRequest request) {
        Long courseId = courseService.create(request);
        return Map.of("id", courseId);
    }
}