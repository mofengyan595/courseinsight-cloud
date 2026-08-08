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
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
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

    @Test
    void shouldAuthorizeCsvExportAndCreateStableFilename() {
        given(batchMapper.selectById(30L)).willReturn(batch(30L, 14L));

        String filename = resultService.authorizeCsvExport(
                30L,
                11L,
                UserRole.TEACHER
        );

        assertThat(filename).isEqualTo("analysis-batch-batch-30.csv");
        verify(accessService).assertCanManage(14L, 11L, UserRole.TEACHER);
    }

    @Test
    void shouldWriteUtf8CsvPageByPageAndEscapeSpreadsheetFormula() throws Exception {
        AnalysisBatchResultRow dangerousRow = exportRow(
                60L,
                "=SUM(1,2)",
                "SUCCESS"
        );
        AnalysisBatchResultRow failedRow = exportRow(
                61L,
                "Normal comment",
                "FAILED"
        );
        failedRow.setFailureReason("AI service unavailable");

        Page<AnalysisBatchResultRow> firstPage = new Page<>(1, 100, false);
        firstPage.setRecords(Collections.nCopies(100, dangerousRow));
        Page<AnalysisBatchResultRow> secondPage = new Page<>(2, 100, false);
        secondPage.setRecords(List.of(failedRow));
        given(resultMapper.selectPageByBatchId(any(), eq(30L)))
                .willReturn(firstPage, secondPage);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        resultService.writeCsv(30L, outputStream);

        String csv = outputStream.toString(StandardCharsets.UTF_8);
        assertThat(csv.charAt(0)).isEqualTo('\uFEFF');
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .get()
                .parse(new StringReader(csv.substring(1)))) {
            assertThat(parser.getRecords())
                    .hasSize(101)
                    .first()
                    .satisfies(record -> assertThat(record.get("评论内容"))
                            .isEqualTo("'=SUM(1,2)"));
        }
        verify(resultMapper, times(2))
                .selectPageByBatchId(any(), eq(30L));
    }

    private AnalysisBatch batch(Long id, Long courseId) {
        AnalysisBatch batch = new AnalysisBatch();
        batch.setId(id);
        batch.setCourseId(courseId);
        batch.setBatchNo("batch-30");
        return batch;
    }

    private AnalysisBatchResultRow exportRow(
            Long taskId,
            String content,
            String status) {
        AnalysisBatchResultRow row = new AnalysisBatchResultRow();
        row.setTaskId(taskId);
        row.setCommentId(taskId + 10);
        row.setContent(content);
        row.setRating(5);
        row.setTaskStatus(status);
        row.setRetryCount(0);
        row.setLanguage("en");
        row.setSentiment("positive");
        row.setKeywordsJson("[\"clear\"]");
        row.setTopicsJson("[\"examples\"]");
        return row;
    }
}
