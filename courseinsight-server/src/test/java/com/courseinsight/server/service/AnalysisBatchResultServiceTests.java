package com.courseinsight.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.AnalysisBatchResultItemResponse;
import com.courseinsight.server.dto.AnalysisBatchResultPageQuery;
import com.courseinsight.server.dto.AnalysisBatchResultRow;
import com.courseinsight.server.entity.AnalysisBatch;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisBatchMapper;
import com.courseinsight.server.mapper.AnalysisBatchResultMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AnalysisBatchResultServiceTests {

    @Mock
    private AnalysisBatchMapper batchMapper;

    @Mock
    private AnalysisBatchResultMapper resultMapper;

    @Mock
    private CourseManagementAccessService accessService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AnalysisBatchResultService resultService;

    @Test
    void shouldReturnAuthorizedBatchResultsAsPage() {
        AnalysisBatch batch = batch(30L, 14L);
        AnalysisBatchResultRow row = new AnalysisBatchResultRow();
        row.setTaskId(60L);
        row.setCommentId(70L);
        row.setTaskStatus("SUCCESS");
        row.setTopicsJson("[\"examples\"]");

        Page<AnalysisBatchResultRow> mapperPage = new Page<>(1, 20, 1);
        mapperPage.setRecords(List.of(row));
        given(batchMapper.selectById(30L)).willReturn(batch);
        given(resultMapper.selectPageByBatchId(any(), any()))
                .willReturn(mapperPage);

        PageResponse<AnalysisBatchResultItemResponse> response = resultService.page(
                30L,
                11L,
                UserRole.TEACHER,
                new AnalysisBatchResultPageQuery(null, null)
        );

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.total()).isEqualTo(1);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.taskId()).isEqualTo(60L);
            assertThat(item.topics().get(0).asText()).isEqualTo("examples");
        });
        verify(accessService).assertCanManage(14L, 11L, UserRole.TEACHER);
    }

    @Test
    void shouldRejectMissingBatchBeforeQueryingResults() {
        given(batchMapper.selectById(999L)).willReturn(null);

        assertThatThrownBy(() -> resultService.page(
                999L,
                11L,
                UserRole.TEACHER,
                new AnalysisBatchResultPageQuery(1, 20)
        )).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(accessService, resultMapper);
    }

    private AnalysisBatch batch(Long id, Long courseId) {
        AnalysisBatch batch = new AnalysisBatch();
        batch.setId(id);
        batch.setCourseId(courseId);
        return batch;
    }
}
