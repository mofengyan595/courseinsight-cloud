package com.courseinsight.server.service;

import com.courseinsight.server.cache.CourseAnalyticsCache;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import org.springframework.stereotype.Service;

@Service
public class AnalysisTaskDeadLetterService {

    private final AnalysisTaskMapper analysisTaskMapper;
    private final CourseAnalyticsCache courseAnalyticsCache;

    public AnalysisTaskDeadLetterService(
            AnalysisTaskMapper analysisTaskMapper,
            CourseAnalyticsCache courseAnalyticsCache) {
        this.analysisTaskMapper = analysisTaskMapper;
        this.courseAnalyticsCache = courseAnalyticsCache;
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
            courseAnalyticsCache.evict(task.getCourseId());
        }
        return updated;
    }
}
