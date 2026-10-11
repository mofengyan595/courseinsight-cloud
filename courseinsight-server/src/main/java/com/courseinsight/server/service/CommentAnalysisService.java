package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.courseinsight.server.dto.AnalysisResultResponse;
import com.courseinsight.server.dto.AnalysisTaskDetailResponse;
import com.courseinsight.server.dto.CommentAnalysisResponse;
import com.courseinsight.server.dto.CommentDetailResponse;
import com.courseinsight.server.entity.AnalysisResult;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.AnalysisTaskStatus;
import com.courseinsight.server.entity.CourseComment;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisResultMapper;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.mapper.CourseCommentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class CommentAnalysisService {

    private final CourseCommentMapper commentMapper;
    private final AnalysisTaskMapper taskMapper;
    private final AnalysisResultMapper resultMapper;
    private final CourseManagementAccessService accessService;
    private final ObjectMapper objectMapper;

    public CommentAnalysisService(
            CourseCommentMapper commentMapper,
            AnalysisTaskMapper taskMapper,
            AnalysisResultMapper resultMapper,
            CourseManagementAccessService accessService,
            ObjectMapper objectMapper) {
        this.commentMapper = commentMapper;
        this.taskMapper = taskMapper;
        this.resultMapper = resultMapper;
        this.accessService = accessService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public CommentAnalysisResponse getByCommentId(Long commentId, Long userId, UserRole role) {
        // 完整分析仅面向教学管理，不沿用学生读取本人 analysis-task 的权限。
        if (role != UserRole.TEACHER && role != UserRole.ADMIN) {
            throw new CourseAccessDeniedException("仅教师或管理员可以查看评价分析");
        }
        CourseComment comment = commentMapper.selectById(commentId);
        if (comment == null || !Objects.equals(comment.getStatus(), 1)) {
            throw new ResourceNotFoundException("课程评价不存在或已删除");
        }
        accessService.assertCanManage(comment.getCourseId(), userId, role);

        AnalysisTask task = taskMapper.selectOne(new LambdaQueryWrapper<AnalysisTask>()
                .eq(AnalysisTask::getCommentId, commentId)
                .eq(AnalysisTask::getCourseId, comment.getCourseId()));
        if (task == null) {
            throw new ResourceNotFoundException("分析任务不存在");
        }
        AnalysisResultResponse result = null;
        if (AnalysisTaskStatus.SUCCESS.name().equals(task.getStatus())) {
            AnalysisResult stored = resultMapper.selectOne(new LambdaQueryWrapper<AnalysisResult>()
                    .eq(AnalysisResult::getTaskId, task.getId())
                    .eq(AnalysisResult::getCommentId, commentId)
                    .eq(AnalysisResult::getCourseId, comment.getCourseId()));
            if (stored == null) {
                throw new ResourceNotFoundException("分析结果不存在");
            }
            result = AnalysisResultResponse.from(stored, objectMapper);
        }
        return new CommentAnalysisResponse(
                CommentDetailResponse.from(comment), AnalysisTaskDetailResponse.from(task), result
        );
    }
}
