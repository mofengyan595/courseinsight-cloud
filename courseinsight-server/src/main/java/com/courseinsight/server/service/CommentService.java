package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.CommentCreateRequest;
import com.courseinsight.server.dto.CommentDetailResponse;
import com.courseinsight.server.dto.CommentPageQuery;
import com.courseinsight.server.entity.CourseComment;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.CourseCommentMapper;
import com.courseinsight.server.mapper.CourseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
        requireCourse(courseId);

        CourseComment comment = new CourseComment();
        comment.setCourseId(courseId);
        comment.setContent(request.content());
        comment.setRating(request.rating());
        comment.setStatus(1);

        courseCommentMapper.insert(comment);
        return comment.getId();
    }

    @Transactional(readOnly = true)
    public PageResponse<CommentDetailResponse> page(Long courseId, CommentPageQuery query) {
        requireCourse(courseId);

        LambdaQueryWrapper<CourseComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CourseComment::getCourseId, courseId)
                .orderByDesc(CourseComment::getCreatedAt)
                .orderByDesc(CourseComment::getId);

        Page<CourseComment> result = courseCommentMapper.selectPage(
                new Page<>(query.page(), query.size()),
                wrapper
        );

        List<CommentDetailResponse> items = result.getRecords()
                .stream()
                .map(CommentDetailResponse::from)
                .toList();

        return new PageResponse<>(
                result.getCurrent(),
                result.getSize(),
                result.getTotal(),
                result.getPages(),
                items
        );
    }

    private void requireCourse(Long courseId) {
        if (courseMapper.selectById(courseId) == null) {
            throw new ResourceNotFoundException("课程不存在");
        }
    }
}
