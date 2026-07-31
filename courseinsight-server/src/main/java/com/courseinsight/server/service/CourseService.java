package com.courseinsight.server.service;

import com.courseinsight.server.dto.CourseCreateRequest;
import com.courseinsight.server.dto.CourseDetailResponse;
import com.courseinsight.server.entity.Course;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.CourseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseService {

    private final CourseMapper courseMapper;

    public CourseService(CourseMapper courseMapper) {
        this.courseMapper = courseMapper;
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
        Course course = courseMapper.selectById(id);
        if (course == null) {
            throw new ResourceNotFoundException("课程不存在");
        }
        return CourseDetailResponse.from(course);
    }
}
