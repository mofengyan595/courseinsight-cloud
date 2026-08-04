package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.courseinsight.server.cache.CourseAnalyticsCache;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.CommentCreateRequest;
import com.courseinsight.server.dto.CommentDetailResponse;
import com.courseinsight.server.dto.CommentPageQuery;
import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.entity.AnalysisOutboxStatus;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.AnalysisTaskStatus;
import com.courseinsight.server.entity.CourseComment;
import com.courseinsight.server.exception.DuplicateCommentException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.mapper.CourseCommentMapper;
import com.courseinsight.server.mapper.CourseMapper;
import com.courseinsight.server.message.AnalysisTaskCreatedEvent;
import com.courseinsight.server.ratelimit.RateLimitPolicy;
import com.courseinsight.server.ratelimit.RedisRateLimiter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class CommentService {

    private final CourseMapper courseMapper;
    private final CourseCommentMapper courseCommentMapper;
    private final AnalysisTaskMapper analysisTaskMapper;
    private final AnalysisOutboxEventMapper outboxEventMapper;
    private final CourseAnalyticsCache courseAnalyticsCache;
    private final RedisRateLimiter rateLimiter;

    public CommentService(
            CourseMapper courseMapper,
            CourseCommentMapper courseCommentMapper,
            AnalysisTaskMapper analysisTaskMapper,
            AnalysisOutboxEventMapper outboxEventMapper,
            CourseAnalyticsCache courseAnalyticsCache,
            RedisRateLimiter rateLimiter) {
        this.courseMapper = courseMapper;
        this.courseCommentMapper = courseCommentMapper;
        this.analysisTaskMapper = analysisTaskMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.courseAnalyticsCache = courseAnalyticsCache;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public Long create(Long courseId, Long userId, CommentCreateRequest request) {
        rateLimiter.check(RateLimitPolicy.COMMENT_SUBMISSION, userId);
        requireCourse(courseId);

        CourseComment comment = new CourseComment();
        comment.setCourseId(courseId);
        comment.setUserId(userId);
        comment.setContent(request.content());
        comment.setRating(request.rating());
        comment.setAnonymous(true);
        comment.setStatus(1);

        try {
            courseCommentMapper.insert(comment);
        } catch (DuplicateKeyException exception) {
            throw new DuplicateCommentException("你已经评价过该课程");
        }

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

        courseAnalyticsCache.evictAfterCommit(courseId);
        return comment.getId();
    }

    @Transactional(readOnly = true)
    public PageResponse<CommentDetailResponse> page(Long courseId, CommentPageQuery query) {
        requireCourse(courseId);

        LambdaQueryWrapper<CourseComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CourseComment::getCourseId, courseId)
                .eq(CourseComment::getStatus, 1)
                .orderByDesc(CourseComment::getCreatedAt)
                .orderByDesc(CourseComment::getId);

        Page<CourseComment> result = courseCommentMapper.selectPage(
                new Page<>(query.page(), query.size()),
                wrapper
        );

        return toPageResponse(result);
    }

    @Transactional(readOnly = true)
    public PageResponse<CommentDetailResponse> pageByUser(
            Long userId,
            CommentPageQuery query) {
        LambdaQueryWrapper<CourseComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CourseComment::getUserId, userId)
                .eq(CourseComment::getStatus, 1)
                .orderByDesc(CourseComment::getCreatedAt)
                .orderByDesc(CourseComment::getId);

        Page<CourseComment> result = courseCommentMapper.selectPage(
                new Page<>(query.page(), query.size()),
                wrapper
        );

        return toPageResponse(result);
    }

    @Transactional
    public void delete(Long commentId, Long userId) {
        CourseComment existing = courseCommentMapper.selectById(commentId);
        if (existing == null
                || !Objects.equals(existing.getUserId(), userId)
                || !Objects.equals(existing.getStatus(), 1)) {
            throw new ResourceNotFoundException("评价不存在");
        }

        CourseComment update = new CourseComment();
        update.setStatus(0);

        LambdaUpdateWrapper<CourseComment> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(CourseComment::getId, commentId)
                .eq(CourseComment::getUserId, userId)
                .eq(CourseComment::getStatus, 1);

        if (courseCommentMapper.update(update, wrapper) != 1) {
            throw new ResourceNotFoundException("评价不存在");
        }
        courseAnalyticsCache.evictAfterCommit(existing.getCourseId());
    }

    private PageResponse<CommentDetailResponse> toPageResponse(Page<CourseComment> result) {
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
