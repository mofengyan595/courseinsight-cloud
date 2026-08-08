package com.courseinsight.server.service;

import com.courseinsight.server.cache.CourseAnalyticsCache;
import com.courseinsight.server.entity.AnalysisTask;
import com.courseinsight.server.mapper.AnalysisTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AnalysisTaskDeadLetterServiceTests {

    @Mock
    private AnalysisTaskMapper taskMapper;

    @Mock
    private CourseAnalyticsCache analyticsCache;

    @InjectMocks
    private AnalysisTaskDeadLetterService service;

    @Test
    void shouldMarkOnlyTheCurrentEligibleGeneration() {
        given(taskMapper.selectById(60L)).willReturn(task());
        given(taskMapper.markCurrentGenerationDeadLettered(
                org.mockito.ArgumentMatchers.eq(60L),
                org.mockito.ArgumentMatchers.eq("event-2")
        )).willReturn(1);

        assertThat(service.markDeadLettered(60L, "event-2")).isTrue();

        verify(analyticsCache).evict(14L);
    }

    @Test
    void shouldIgnoreStaleOrLiveOwnerGeneration() {
        given(taskMapper.selectById(60L)).willReturn(task());
        given(taskMapper.markCurrentGenerationDeadLettered(
                org.mockito.ArgumentMatchers.eq(60L),
                org.mockito.ArgumentMatchers.eq("event-1")
        )).willReturn(0);

        assertThat(service.markDeadLettered(60L, "event-1")).isFalse();

        verifyNoInteractions(analyticsCache);
    }

    private AnalysisTask task() {
        AnalysisTask task = new AnalysisTask();
        task.setId(60L);
        task.setCourseId(14L);
        task.setStatus("PROCESSING");
        return task;
    }
}
