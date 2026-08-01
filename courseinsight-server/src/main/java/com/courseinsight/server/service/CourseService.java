package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.courseinsight.server.cache.CourseCacheLookup;
import com.courseinsight.server.cache.CourseDetailCache;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.CourseCreateRequest;
import com.courseinsight.server.dto.CourseDetailResponse;
import com.courseinsight.server.dto.CoursePageQuery;
import com.courseinsight.server.entity.Course;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.CourseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CourseService {

    private final CourseMapper courseMapper;
    private final CourseDetailCache courseDetailCache;

    public CourseService(CourseMapper courseMapper, CourseDetailCache courseDetailCache) {
        this.courseMapper = courseMapper;
        this.courseDetailCache = courseDetailCache;
    }

    @Transactional
    public Long create(CourseCreateRequest request) {
        Course course = new Course();
        course.setCode(request.code());
        course.setName(request.name());
        course.setTeacherName(request.teacherName());
        course.setDescription(request.description());
        course.setStatus(1);

        courseMapper.insert(course);
        return course.getId();
    }

    @Transactional(readOnly = true)
    public CourseDetailResponse getById(Long id) {
        CourseCacheLookup cacheLookup = courseDetailCache.get(id);
        if (cacheLookup.hit()) {
            if (cacheLookup.course() == null) {
                throw new ResourceNotFoundException("课程不存在");
            }
            return cacheLookup.course();
        }

        Course course = courseMapper.selectById(id);
        if (course == null) {
            courseDetailCache.putNotFound(id);
            throw new ResourceNotFoundException("课程不存在");
        }

        CourseDetailResponse response = CourseDetailResponse.from(course);
        courseDetailCache.put(id, response);
        return response;
    }

    @Transactional(readOnly = true)
    public PageResponse<CourseDetailResponse> page(CoursePageQuery query) {
        LambdaQueryWrapper<Course> wrapper = new LambdaQueryWrapper<>();

        if (query.keyword() != null) {
            wrapper.and(condition -> condition
                    .like(Course::getCode, query.keyword())
                    .or()
                    .like(Course::getName, query.keyword())
                    .or()
                    .like(Course::getTeacherName, query.keyword()));
        }

        wrapper.orderByDesc(Course::getCreatedAt)
                .orderByDesc(Course::getId);

        Page<Course> result = courseMapper.selectPage(
                new Page<>(query.page(), query.size()),
                wrapper
        );

        List<CourseDetailResponse> items = result.getRecords()
                .stream()
                .map(CourseDetailResponse::from)
                .toList();

        return new PageResponse<>(
                result.getCurrent(),
                result.getSize(),
                result.getTotal(),
                result.getPages(),
                items
        );
    }
}
