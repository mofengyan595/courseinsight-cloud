package com.courseinsight.server.service;

import com.courseinsight.server.cache.CourseAnalyticsCache;
import com.courseinsight.server.cache.CoursePopularityRankingCache;
import com.courseinsight.server.dto.AnalysisBatchCommentRow;
import com.courseinsight.server.dto.AnalysisBatchCreateResponse;
import com.courseinsight.server.entity.AnalysisBatch;
import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.entity.AnalysisOutboxStatus;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.entity.CourseComment;
import com.courseinsight.server.mapper.AnalysisBatchMapper;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import com.courseinsight.server.mapper.CourseCommentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AnalysisBatchCreationServiceTests {

    @Mock
    private AnalysisBatchMapper batchMapper;

    @Mock
    private CourseCommentMapper commentMapper;

    @Mock
    private AnalysisTaskMapper taskMapper;

    @Mock
    private AnalysisOutboxEventMapper outboxEventMapper;

    @Mock
    private CourseAnalyticsCache analyticsCache;

    @Mock
    private CoursePopularityRankingCache rankingCache;

    @InjectMocks
    private AnalysisBatchCreationService creationService;

    @Test
    void shouldCreateBatchCommentsTasksAndOutboxEvents() {
        given(batchMapper.insert(any(AnalysisBatch.class))).willAnswer(invocation -> {
            AnalysisBatch batch = invocation.getArgument(0);
            batch.setId(30L);
            return 1;
        });
        AtomicLong commentIds = new AtomicLong(100);
        given(commentMapper.insert(any(CourseComment.class))).willAnswer(invocation -> {
            CourseComment comment = invocation.getArgument(0);
            comment.setId(commentIds.getAndIncrement());
            return 1;
        });
        AtomicLong taskIds = new AtomicLong(200);
        given(taskMapper.insert(any(AnalysisTask.class))).willAnswer(invocation -> {
            AnalysisTask task = invocation.getArgument(0);
            task.setId(taskIds.getAndIncrement());
            return 1;
        });
        given(outboxEventMapper.insert(any(AnalysisOutboxEvent.class))).willReturn(1);

        AnalysisBatchCreateResponse response = creationService.create(
                14L,
                11L,
                "comments.csv",
                List.of(
                        new AnalysisBatchCommentRow(2, "讲解清晰", 5),
                        new AnalysisBatchCommentRow(3, "进度太快", 2)
                )
        );

        assertThat(response.batchId()).isEqualTo(30L);
        assertThat(response.totalCount()).isEqualTo(2);

        ArgumentCaptor<AnalysisBatch> batchCaptor =
                ArgumentCaptor.forClass(AnalysisBatch.class);
        verify(batchMapper).insert(batchCaptor.capture());
        assertThat(batchCaptor.getValue().getCreatedBy()).isEqualTo(11L);
        assertThat(batchCaptor.getValue().getTotalCount()).isEqualTo(2);

        ArgumentCaptor<CourseComment> commentCaptor =
                ArgumentCaptor.forClass(CourseComment.class);
        verify(commentMapper, times(2)).insert(commentCaptor.capture());
        assertThat(commentCaptor.getAllValues())
                .allSatisfy(comment -> {
                    assertThat(comment.getUserId()).isNull();
                    assertThat(comment.getAnonymous()).isTrue();
                    assertThat(comment.getStatus()).isEqualTo(1);
                });

        ArgumentCaptor<AnalysisTask> taskCaptor =
                ArgumentCaptor.forClass(AnalysisTask.class);
        verify(taskMapper, times(2)).insert(taskCaptor.capture());
        assertThat(taskCaptor.getAllValues())
                .allSatisfy(task -> assertThat(task.getBatchId()).isEqualTo(30L));

        ArgumentCaptor<AnalysisOutboxEvent> outboxCaptor =
                ArgumentCaptor.forClass(AnalysisOutboxEvent.class);
        verify(outboxEventMapper, times(2)).insert(outboxCaptor.capture());
        assertThat(outboxCaptor.getAllValues())
                .allSatisfy(event -> {
                    assertThat(event.getStatus())
                            .isEqualTo(AnalysisOutboxStatus.PENDING.name());
                    assertThat(event.getNextRetryAt()).isNotNull();
                });
        verify(analyticsCache).evictAfterCommit(14L);
        verify(rankingCache).evictAfterCommit();
    }
}
