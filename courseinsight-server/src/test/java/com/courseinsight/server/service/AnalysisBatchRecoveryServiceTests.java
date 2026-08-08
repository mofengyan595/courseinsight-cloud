package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.courseinsight.server.dto.AnalysisBatchRetryResponse;
import com.courseinsight.server.entity.AnalysisBatch;
import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisBatchMapper;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AnalysisBatchRecoveryServiceTests {

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AnalysisTask.class
        );
    }

    @Mock
    private AnalysisBatchMapper batchMapper;

    @Mock
    private AnalysisTaskMapper taskMapper;

    @Mock
    private AnalysisOutboxEventMapper outboxEventMapper;

    @Mock
    private CourseManagementAccessService accessService;

    @InjectMocks
    private AnalysisBatchRecoveryService recoveryService;

    @Test
    void shouldRequeueDeadLetteredTasksWithNewOutboxEvents() {
        given(batchMapper.selectById(30L)).willReturn(batch());
        given(taskMapper.selectDeadLetteredByBatchIdForUpdate(30L))
                .willReturn(List.of(task(60L, 70L), task(61L, 71L)));
        given(taskMapper.recoverDeadLetteredWithNewGeneration(
                any(Long.class),
                any(String.class),
                any(String.class)
        )).willReturn(1);
        given(outboxEventMapper.insert(any(AnalysisOutboxEvent.class)))
                .willReturn(1);

        AnalysisBatchRetryResponse response = recoveryService.retryDeadLettered(
                30L,
                11L,
                UserRole.TEACHER
        );

        assertThat(response.batchId()).isEqualTo(30L);
        assertThat(response.requeuedCount()).isEqualTo(2);
        ArgumentCaptor<AnalysisOutboxEvent> eventCaptor =
                ArgumentCaptor.forClass(AnalysisOutboxEvent.class);
        verify(outboxEventMapper, times(2)).insert(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(AnalysisOutboxEvent::getTaskId)
                .containsExactly(60L, 61L);
        assertThat(eventCaptor.getAllValues())
                .extracting(AnalysisOutboxEvent::getStatus)
                .containsOnly("PENDING");
        assertThat(eventCaptor.getAllValues())
                .extracting(AnalysisOutboxEvent::getEventId)
                .doesNotHaveDuplicates();
        verify(accessService).assertCanManage(14L, 11L, UserRole.TEACHER);
    }

    @Test
    void shouldReturnZeroWhenNoDeadLetteredTaskRemains() {
        given(batchMapper.selectById(30L)).willReturn(batch());
        given(taskMapper.selectDeadLetteredByBatchIdForUpdate(30L))
                .willReturn(List.of());

        AnalysisBatchRetryResponse response = recoveryService.retryDeadLettered(
                30L,
                11L,
                UserRole.TEACHER
        );

        assertThat(response.requeuedCount()).isZero();
        verifyNoInteractions(outboxEventMapper);
    }

    @Test
    void shouldRejectMissingBatchBeforeLockingTasks() {
        given(batchMapper.selectById(999L)).willReturn(null);

        assertThatThrownBy(() -> recoveryService.retryDeadLettered(
                999L,
                11L,
                UserRole.TEACHER
        )).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(accessService, taskMapper, outboxEventMapper);
    }

    private AnalysisBatch batch() {
        AnalysisBatch batch = new AnalysisBatch();
        batch.setId(30L);
        batch.setCourseId(14L);
        return batch;
    }

    private AnalysisTask task(Long taskId, Long commentId) {
        AnalysisTask task = new AnalysisTask();
        task.setId(taskId);
        task.setCommentId(commentId);
        task.setCourseId(14L);
        task.setBatchId(30L);
        task.setStatus("FAILED");
        task.setCurrentEventId("event-" + taskId);
        return task;
    }
}
