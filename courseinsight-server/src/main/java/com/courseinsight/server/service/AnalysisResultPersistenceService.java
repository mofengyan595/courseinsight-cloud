package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.courseinsight.server.cache.CourseAnalyticsCache;
import com.courseinsight.server.client.AiAnalysisResponse;
import com.courseinsight.server.dto.AnalysisExecutionResponse;
import com.courseinsight.server.entity.AnalysisResult;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.exception.AnalysisTaskConflictException;
import com.courseinsight.server.exception.StaleAnalysisExecutionException;
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
    private final CourseAnalyticsCache courseAnalyticsCache;

    public AnalysisResultPersistenceService(
            AnalysisResultMapper analysisResultMapper,
            AnalysisTaskMapper analysisTaskMapper,
            ObjectMapper objectMapper,
            CourseAnalyticsCache courseAnalyticsCache) {
        this.analysisResultMapper = analysisResultMapper;
        this.analysisTaskMapper = analysisTaskMapper;
        this.objectMapper = objectMapper;
        this.courseAnalyticsCache = courseAnalyticsCache;
    }

    @Transactional
    public AnalysisExecutionResponse saveSuccess(
            AnalysisTask task,
            AiAnalysisResponse response,
            String eventId,
            String executionToken) {
        LocalDateTime completedAt = LocalDateTime.now();
        if (analysisTaskMapper.completeOwnedExecution(
                task.getId(),
                eventId,
                executionToken,
                completedAt
        ) != 1) {
            throw new StaleAnalysisExecutionException(
                    "分析任务执行所有权已变更"
            );
        }

        AnalysisResult result = toEntity(task, response);
        analysisResultMapper.insert(result);

        courseAnalyticsCache.evictAfterCommit(task.getCourseId());
        return AnalysisExecutionResponse.success(result);
    }

    @Transactional(readOnly = true)
    public AnalysisExecutionResponse getSuccessResult(Long taskId) {
        LambdaQueryWrapper<AnalysisResult> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisResult::getTaskId, taskId);
        AnalysisResult result = analysisResultMapper.selectOne(wrapper);
        if (result == null) {
            throw new AnalysisTaskConflictException(
                    "任务已成功，但分析结果不存在"
            );
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
            throw new IllegalStateException(
                    "分析结果 JSON 序列化失败",
                    exception
            );
        }
    }
}
