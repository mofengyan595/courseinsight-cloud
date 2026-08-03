package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.courseinsight.server.cache.CourseAnalyticsCache;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.AnalysisTaskStatus;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

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

    public boolean markDeadLettered(Long taskId) {
        AnalysisTask task = analysisTaskMapper.selectById(taskId);
        if (task == null) {
            return false;
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
}
