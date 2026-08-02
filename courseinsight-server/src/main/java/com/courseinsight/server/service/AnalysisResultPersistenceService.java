package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.courseinsight.server.client.AiAnalysisResponse;
import com.courseinsight.server.dto.AnalysisExecutionResponse;
import com.courseinsight.server.entity.AnalysisResult;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.AnalysisTaskStatus;
import com.courseinsight.server.exception.AnalysisTaskConflictException;
import com.courseinsight.server.mapper.AnalysisResultMapper;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AnalysisResultPersistenceService {

    private final AnalysisResultMapper analysisResultMapper;
    private final AnalysisTaskMapper analysisTaskMapper;
    private final ObjectMapper objectMapper;

    public AnalysisResultPersistenceService(
            AnalysisResultMapper analysisResultMapper,
            AnalysisTaskMapper analysisTaskMapper,
            ObjectMapper objectMapper) {
        this.analysisResultMapper = analysisResultMapper;
        this.analysisTaskMapper = analysisTaskMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AnalysisExecutionResponse saveSuccess(
            AnalysisTask task,
            AiAnalysisResponse response) {
        AnalysisResult result = toEntity(task, response);
        analysisResultMapper.insert(result);

        LocalDateTime completedAt = LocalDateTime.now();
        LambdaUpdateWrapper<AnalysisTask> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AnalysisTask::getId, task.getId())
                .eq(AnalysisTask::getStatus, AnalysisTaskStatus.PROCESSING.name())
                .set(AnalysisTask::getStatus, AnalysisTaskStatus.SUCCESS.name())
                .set(AnalysisTask::getFailureReason, null)
                .set(AnalysisTask::getCompletedAt, completedAt);

        if (analysisTaskMapper.update(null, wrapper) != 1) {
            throw new AnalysisTaskConflictException("分析任务状态已发生变化");
        }

        return AnalysisExecutionResponse.success(result);
    }

    @Transactional(readOnly = true)
    public AnalysisExecutionResponse getSuccessResult(Long taskId) {
        LambdaQueryWrapper<AnalysisResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisResult::getTaskId, taskId);
        AnalysisResult result = analysisResultMapper.selectOne(wrapper);
        if (result == null) {
            throw new AnalysisTaskConflictException("任务已成功，但分析结果不存在");
        }
        return AnalysisExecutionResponse.success(result);
    }

    private AnalysisResult toEntity(
            AnalysisTask task,
            AiAnalysisResponse response) {
        AnalysisResult result = new AnalysisResult();
        result.setTaskId(task.getId());
        result.setCommentId(task.getCommentId());
        result.setCourseId(task.getCourseId());
        result.setLanguage(response.language());
        result.setSentiment(response.sentiment());
        result.setConfidence(response.confidence());
        result.setSentimentSource(response.sentimentSource());
        result.setSentimentDevice(response.sentimentDevice());
        result.setTopicsJson(toJson(response.topics()));
        result.setTopicEvidenceJson(toJson(response.topicEvidence()));
        result.setKeywordsJson(toJson(response.keywords()));
        result.setLongTextHandled(response.longTextHandled());
        result.setLongTextTruncated(response.longTextTruncated());

        if (response.advice() != null) {
            result.setAdviceJson(toJson(response.advice()));
            result.setRiskLevel(response.advice().riskLevel());
            result.setAdviceSource(response.advice().source());
        }
        return result;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("分析结果 JSON 序列化失败", exception);
        }
    }
}
