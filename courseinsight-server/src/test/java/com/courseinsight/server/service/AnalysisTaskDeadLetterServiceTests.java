package com.courseinsight.server.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.courseinsight.server.cache.CourseAnalyticsCache;
import com.courseinsight.server.entity.AnalysisOutboxEvent;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AnalysisTaskDeadLetterServiceTests {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AnalysisTask.class);
        TableInfoHelper.initTableInfo(assistant, AnalysisOutboxEvent.class);
    }

    @Mock
    private AnalysisTaskMapper taskMapper;

    @Mock
    private AnalysisOutboxEventMapper outboxEventMapper;

    @Mock
    private CourseAnalyticsCache analyticsCache;

    @Test
    void shouldMarkDeadLetterOnlyForLatestReplayEvent() {
        AnalysisTask task = task();
        AnalysisOutboxEvent event = event("new-event");
        given(taskMapper.selectById(60L)).willReturn(task);
        given(outboxEventMapper.selectOne(any())).willReturn(event);
        given(taskMapper.update(isNull(), any())).willReturn(1);
        AnalysisTaskDeadLetterService service = service();

        boolean marked = service.markDeadLettered(60L, "new-event");

        assertThat(marked).isTrue();
        verify(analyticsCache).evict(14L);
    }

    @Test
    void shouldIgnoreDeadLetterFromOlderReplayEvent() {
        given(taskMapper.selectById(60L)).willReturn(task());
        given(outboxEventMapper.selectOne(any()))
                .willReturn(event("new-event"));
        AnalysisTaskDeadLetterService service = service();

        boolean marked = service.markDeadLettered(60L, "old-event");

        assertThat(marked).isFalse();
        verifyNoInteractions(analyticsCache);
    }

    @Test
    void shouldKeepExistingBehaviorForNonBatchTaskWithoutOutboxLookup() {
        AnalysisTask task = task();
        task.setBatchId(null);
        given(taskMapper.selectById(60L)).willReturn(task);
        given(taskMapper.update(isNull(), any())).willReturn(1);
        AnalysisTaskDeadLetterService service = service();

        boolean marked = service.markDeadLettered(60L, "manual-event");

        assertThat(marked).isTrue();
        verifyNoInteractions(outboxEventMapper);
        verify(analyticsCache).evict(14L);
    }

    private AnalysisTaskDeadLetterService service() {
        return new AnalysisTaskDeadLetterService(
                taskMapper,
                outboxEventMapper,
                analyticsCache
        );
    }

    private AnalysisTask task() {
        AnalysisTask task = new AnalysisTask();
        task.setId(60L);
        task.setCourseId(14L);
        task.setBatchId(30L);
        task.setStatus("FAILED");
        return task;
    }

    private AnalysisOutboxEvent event(String eventId) {
        AnalysisOutboxEvent event = new AnalysisOutboxEvent();
        event.setId(90L);
        event.setEventId(eventId);
        event.setTaskId(60L);
        return event;
    }
}
