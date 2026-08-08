package com.courseinsight.server.service;

import com.courseinsight.server.cache.CourseAnalyticsCache;
import com.courseinsight.server.cache.CoursePopularityRankingCache;
import com.courseinsight.server.dto.AnalysisBatchCommentRow;
import com.courseinsight.server.dto.AnalysisBatchCreateResponse;
import com.courseinsight.server.entity.AnalysisBatch;
import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.entity.AnalysisOutboxStatus;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.AnalysisTaskStatus;
import com.courseinsight.server.entity.CourseComment;
import com.courseinsight.server.mapper.AnalysisBatchMapper;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.mapper.CourseCommentMapper;
import com.courseinsight.server.message.AnalysisTaskCreatedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AnalysisBatchCreationService {

    private final AnalysisBatchMapper batchMapper;
    private final CourseCommentMapper commentMapper;
    private final AnalysisTaskMapper taskMapper;
    private final AnalysisOutboxEventMapper outboxEventMapper;
    private final CourseAnalyticsCache analyticsCache;
    private final CoursePopularityRankingCache rankingCache;

    public AnalysisBatchCreationService(
            AnalysisBatchMapper batchMapper,
            CourseCommentMapper commentMapper,
            AnalysisTaskMapper taskMapper,
            AnalysisOutboxEventMapper outboxEventMapper,
            CourseAnalyticsCache analyticsCache,
            CoursePopularityRankingCache rankingCache) {
        this.batchMapper = batchMapper;
        this.commentMapper = commentMapper;
        this.taskMapper = taskMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.analyticsCache = analyticsCache;
        this.rankingCache = rankingCache;
    }

    @Transactional
    public AnalysisBatchCreateResponse create(
            Long courseId,
            Long createdBy,
            String originalFilename,
            List<AnalysisBatchCommentRow> rows) {
        AnalysisBatch batch = new AnalysisBatch();
        batch.setBatchNo(randomId());
        batch.setCourseId(courseId);
        batch.setCreatedBy(createdBy);
        batch.setOriginalFilename(originalFilename);
        batch.setTotalCount(rows.size());
        batchMapper.insert(batch);

        LocalDateTime now = LocalDateTime.now();
        for (AnalysisBatchCommentRow row : rows) {
            CourseComment comment = createComment(courseId, row);
            commentMapper.insert(comment);

            String eventId = randomId();
            AnalysisTask task = createTask(
                    batch.getId(),
                    courseId,
                    comment.getId(),
                    eventId
            );
            taskMapper.insert(task);

            AnalysisOutboxEvent outboxEvent = createOutboxEvent(
                    task.getId(),
                    comment.getId(),
                    eventId,
                    now
            );
            outboxEventMapper.insert(outboxEvent);
        }

        analyticsCache.evictAfterCommit(courseId);
        rankingCache.evictAfterCommit();
        return new AnalysisBatchCreateResponse(
                batch.getId(),
                batch.getBatchNo(),
                courseId,
                rows.size()
        );
    }

    private CourseComment createComment(
            Long courseId,
            AnalysisBatchCommentRow row) {
        CourseComment comment = new CourseComment();
        comment.setCourseId(courseId);
        comment.setUserId(null);
        comment.setContent(row.content());
        comment.setRating(row.rating());
        comment.setAnonymous(true);
        comment.setStatus(1);
        return comment;
    }

    private AnalysisTask createTask(
            Long batchId,
            Long courseId,
            Long commentId,
            String eventId) {
        AnalysisTask task = new AnalysisTask();
        task.setTaskNo(randomId());
        task.setCommentId(commentId);
        task.setCourseId(courseId);
        task.setBatchId(batchId);
        task.setStatus(AnalysisTaskStatus.WAITING.name());
        task.setRetryCount(0);
        task.setCurrentEventId(eventId);
        return task;
    }

    private AnalysisOutboxEvent createOutboxEvent(
            Long taskId,
            Long commentId,
            String eventId,
            LocalDateTime nextRetryAt) {
        AnalysisOutboxEvent event = new AnalysisOutboxEvent();
        event.setEventId(eventId);
        event.setTaskId(taskId);
        event.setCommentId(commentId);
        event.setEventType(AnalysisTaskCreatedEvent.EVENT_TYPE);
        event.setStatus(AnalysisOutboxStatus.PENDING.name());
        event.setRetryCount(0);
        event.setNextRetryAt(nextRetryAt);
        return event;
    }

    private String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
