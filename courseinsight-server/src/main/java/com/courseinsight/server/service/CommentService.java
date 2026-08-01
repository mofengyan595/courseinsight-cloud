package com.courseinsight.server.service;

import com.courseinsight.server.dto.CommentCreateRequest;
import com.courseinsight.server.entity.CourseComment;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.CourseCommentMapper;
import com.courseinsight.server.mapper.CourseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommentService {

    private final CourseMapper courseMapper;
    private final CourseCommentMapper courseCommentMapper;

    public CommentService(CourseMapper courseMapper, CourseCommentMapper courseCommentMapper) {
        this.courseMapper = courseMapper;
        this.courseCommentMapper = courseCommentMapper;
    }

    @Transactional
    public Long create(Long courseId, CommentCreateRequest request) {
        if (courseMapper.selectById(courseId) == null) {
            throw new ResourceNotFoundException("课程不存在");
        }

        CourseComment comment = new CourseComment();
        comment.setCourseId(courseId);
        comment.setContent(request.content());
        comment.setRating(request.rating());
        comment.setStatus(1);

        courseCommentMapper.insert(comment);
        return comment.getId();
    }
}
