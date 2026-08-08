package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.courseinsight.server.dto.AnalysisTaskDetailResponse;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.CourseComment;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.CourseAccessDeniedException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.mapper.CourseCommentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class AnalysisTaskService {

    private final AnalysisTaskMapper analysisTaskMapper;
    private final CourseCommentMapper commentMapper;
    private final CourseManagementAccessService managementAccessService;

    public AnalysisTaskService(
            AnalysisTaskMapper analysisTaskMapper,
            CourseCommentMapper commentMapper,
            CourseManagementAccessService managementAccessService) {
        this.analysisTaskMapper = analysisTaskMapper;
        this.commentMapper = commentMapper;
        this.managementAccessService = managementAccessService;
    }

    @Transactional(readOnly = true)
    public AnalysisTaskDetailResponse getByCommentId(
            Long commentId,
            Long currentUserId,
            UserRole currentRole) {
        LambdaQueryWrapper<AnalysisTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AnalysisTask::getCommentId, commentId);

        AnalysisTask task = analysisTaskMapper.selectOne(wrapper);
        if (task == null) {
            throw new ResourceNotFoundException("分析任务不存在");
        }
        CourseComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new ResourceNotFoundException("课程评价不存在");
        }

        authorize(task, comment, currentUserId, currentRole);
        return AnalysisTaskDetailResponse.from(task);
    }

    private void authorize(
            AnalysisTask task,
            CourseComment comment,
            Long currentUserId,
            UserRole currentRole) {
        if (currentRole == UserRole.STUDENT) {
            if (!Objects.equals(comment.getUserId(), currentUserId)) {
                throw new CourseAccessDeniedException(
                        "无权查看其他用户的分析任务"
                );
            }
            return;
        }
        managementAccessService.assertCanManage(
                task.getCourseId(),
                currentUserId,
                currentRole
        );
    }
}
