package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.courseinsight.server.dto.AnalysisTaskDetailResponse;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"rawtypes", "unchecked"})
class AnalysisTaskServiceTests {

    @Mock
    private AnalysisTaskMapper analysisTaskMapper;

    @InjectMocks
    private AnalysisTaskService analysisTaskService;

    @Test
    void shouldGetTaskByCommentId() {
        AnalysisTask task = createTask();
        given(analysisTaskMapper.selectOne(any(Wrapper.class))).willReturn(task);

        AnalysisTaskDetailResponse response = analysisTaskService.getByCommentId(10L);

        assertThat(response.id()).isEqualTo(3L);
        assertThat(response.taskNo()).isEqualTo("1234567890abcdef1234567890abcdef");
        assertThat(response.commentId()).isEqualTo(10L);
        assertThat(response.courseId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo("WAITING");
        assertThat(response.retryCount()).isZero();
        assertThat(response.deadLetteredAt()).isEqualTo(task.getDeadLetteredAt());
    }

    @Test
    void shouldThrowWhenTaskDoesNotExist() {
        given(analysisTaskMapper.selectOne(any(Wrapper.class))).willReturn(null);

        assertThatThrownBy(() -> analysisTaskService.getByCommentId(999999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("分析任务不存在");
    }

    private AnalysisTask createTask() {
        AnalysisTask task = new AnalysisTask();
        task.setId(3L);
        task.setTaskNo("1234567890abcdef1234567890abcdef");
        task.setCommentId(10L);
        task.setCourseId(1L);
        task.setStatus("WAITING");
        task.setRetryCount(0);
        task.setDeadLetteredAt(LocalDateTime.of(2026, 8, 3, 12, 0));
        return task;
    }
}
