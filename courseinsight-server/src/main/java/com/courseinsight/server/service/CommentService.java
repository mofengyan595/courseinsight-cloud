package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.CommentCreateRequest;
import com.courseinsight.server.dto.CommentDetailResponse;
import com.courseinsight.server.dto.CommentPageQuery;
import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.entity.AnalysisOutboxStatus;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.AnalysisTaskStatus;
import com.courseinsight.server.entity.CourseComment;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.mapper.CourseCommentMapper;
import com.courseinsight.server.mapper.CourseMapper;
import com.courseinsight.server.message.AnalysisTaskCreatedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class CommentService {

    private final CourseMapper courseMapper;
    private final CourseCommentMapper courseCommentMapper;
    private final AnalysisTaskMapper analysisTaskMapper;
    private final AnalysisOutboxEventMapper outboxEventMapper;

    public CommentService(
            CourseMapper courseMapper,
            CourseCommentMapper courseCommentMapper,
            AnalysisTaskMapper analysisTaskMapper,
            AnalysisOutboxEventMapper outboxEventMapper) {
        this.courseMapper = courseMapper;
        this.courseCommentMapper = courseCommentMapper;
        this.analysisTaskMapper = analysisTaskMapper;
        this.outboxEventMapper = outboxEventMapper;
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

        AnalysisTask task = new AnalysisTask();
        task.setTaskNo(UUID.randomUUID().toString().replace("-", ""));
        task.setCommentId(comment.getId());
        task.setCourseId(courseId);
        task.setStatus(AnalysisTaskStatus.WAITING.name());
        task.setRetryCount(0);
        analysisTaskMapper.insert(task);

        AnalysisOutboxEvent outboxEvent = new AnalysisOutboxEvent();
        outboxEvent.setEventId(UUID.randomUUID().toString().replace("-", ""));
        outboxEvent.setTaskId(task.getId());
        outboxEvent.setCommentId(comment.getId());
        outboxEvent.setEventType(AnalysisTaskCreatedEvent.EVENT_TYPE);
        outboxEvent.setStatus(AnalysisOutboxStatus.PENDING.name());
        outboxEvent.setRetryCount(0);
        outboxEvent.setNextRetryAt(LocalDateTime.now());
        outboxEventMapper.insert(outboxEvent);

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
