package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.courseinsight.server.dto.AnalysisTaskDetailResponse;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisTaskService {

    private final AnalysisTaskMapper analysisTaskMapper;

    public AnalysisTaskService(AnalysisTaskMapper analysisTaskMapper) {
        this.analysisTaskMapper = analysisTaskMapper;
    }

    @Transactional(readOnly = true)
    public AnalysisTaskDetailResponse getByCommentId(Long commentId) {
        LambdaQueryWrapper<AnalysisTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisTask::getCommentId, commentId);

        AnalysisTask task = analysisTaskMapper.selectOne(wrapper);
        if (task == null) {
            throw new ResourceNotFoundException("分析任务不存在");
        }

        return AnalysisTaskDetailResponse.from(task);
    }
}
