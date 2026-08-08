package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.courseinsight.server.cache.CourseAnalyticsCache;
import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.AnalysisTaskStatus;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AnalysisTaskDeadLetterService {

    private final AnalysisTaskMapper analysisTaskMapper;
    private final AnalysisOutboxEventMapper outboxEventMapper;
    private final CourseAnalyticsCache courseAnalyticsCache;

    public AnalysisTaskDeadLetterService(
            AnalysisTaskMapper analysisTaskMapper,
            AnalysisOutboxEventMapper outboxEventMapper,
            CourseAnalyticsCache courseAnalyticsCache) {
        this.analysisTaskMapper = analysisTaskMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.courseAnalyticsCache = courseAnalyticsCache;
    }

    public boolean markDeadLettered(Long taskId, String eventId) {
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            return false;
        }
        if (task.getBatchId() != null) {
            AnalysisOutboxEvent latestEvent = latestEvent(taskId);
            if (latestEvent == null || !latestEvent.getEventId().equals(eventId)) {
                return false;
            }
        }

        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<AnalysisTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AnalysisTask::getId, taskId)
                .ne(AnalysisTask::getStatus, AnalysisTaskStatus.SUCCESS.name())
                .set(AnalysisTask::getStatus, AnalysisTaskStatus.FAILED.name())
                .set(AnalysisTask::getCompletedAt, now)
                .set(AnalysisTask::getDeadLetteredAt, now);

        boolean updated = analysisTaskMapper.update(null, wrapper) == 1;
        if (updated) {
            courseAnalyticsCache.evict(task.getCourseId());
        }
        return updated;
    }

    private AnalysisOutboxEvent latestEvent(Long taskId) {
        LambdaQueryWrapper<AnalysisOutboxEvent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisOutboxEvent::getTaskId, taskId)
                .orderByDesc(AnalysisOutboxEvent::getId)
                .last("LIMIT 1");
        return outboxEventMapper.selectOne(wrapper);
    }
}
