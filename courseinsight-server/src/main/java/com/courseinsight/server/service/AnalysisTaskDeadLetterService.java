package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.AnalysisTaskStatus;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AnalysisTaskDeadLetterService {

    private final AnalysisTaskMapper analysisTaskMapper;

    public AnalysisTaskDeadLetterService(AnalysisTaskMapper analysisTaskMapper) {
        this.analysisTaskMapper = analysisTaskMapper;
    }

    public boolean markDeadLettered(Long taskId) {
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<AnalysisTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AnalysisTask::getId, taskId)
                .ne(AnalysisTask::getStatus, AnalysisTaskStatus.SUCCESS.name())
                .set(AnalysisTask::getStatus, AnalysisTaskStatus.FAILED.name())
                .set(AnalysisTask::getCompletedAt, now)
                .set(AnalysisTask::getDeadLetteredAt, now);

        return analysisTaskMapper.update(null, wrapper) == 1;
    }
}
