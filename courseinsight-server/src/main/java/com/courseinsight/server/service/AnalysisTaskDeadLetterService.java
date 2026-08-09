package com.courseinsight.server.service;

import com.courseinsight.server.cache.CourseAnalyticsCache;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.metrics.CourseInsightMetrics;
import org.springframework.stereotype.Service;

@Service
public class AnalysisTaskDeadLetterService {

    private final AnalysisTaskMapper analysisTaskMapper;
    private final CourseAnalyticsCache courseAnalyticsCache;
    private final CourseInsightMetrics metrics;

    public AnalysisTaskDeadLetterService(
            AnalysisTaskMapper analysisTaskMapper,
            CourseAnalyticsCache courseAnalyticsCache,
            CourseInsightMetrics metrics) {
        this.analysisTaskMapper = analysisTaskMapper;
        this.courseAnalyticsCache = courseAnalyticsCache;
        this.metrics = metrics;
    }

    public boolean markDeadLettered(Long taskId, String eventId) {
        if (eventId == null) {
            return false;
        }
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            return false;
        }

        boolean updated = analysisTaskMapper.markCurrentGenerationDeadLettered(
                taskId,
                eventId
        ) == 1;
        if (updated) {
            metrics.analysisTaskDeadLettered();
            courseAnalyticsCache.evict(task.getCourseId());
        }
        return updated;
    }
}
