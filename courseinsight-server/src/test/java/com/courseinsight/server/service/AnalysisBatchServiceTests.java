package com.courseinsight.server.service;

import com.courseinsight.server.dto.AnalysisBatchCommentRow;
import com.courseinsight.server.dto.AnalysisBatchCreateResponse;
import com.courseinsight.server.dto.AnalysisBatchCsvData;
import com.courseinsight.server.dto.AnalysisBatchProgressAggregate;
import com.courseinsight.server.dto.AnalysisBatchProgressResponse;
import com.courseinsight.server.entity.AnalysisBatch;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.RateLimitExceededException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.mapper.AnalysisBatchMapper;
import com.courseinsight.server.mapper.AnalysisBatchProgressMapper;
import com.courseinsight.server.ratelimit.RateLimitPolicy;
import com.courseinsight.server.ratelimit.RedisRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AnalysisBatchServiceTests {

    @Mock
    private CourseManagementAccessService accessService;

    @Mock
    private AnalysisBatchCsvParser csvParser;

    @Mock
    private AnalysisBatchCreationService creationService;

    @Mock
    private AnalysisBatchMapper batchMapper;

    @Mock
    private AnalysisBatchProgressMapper progressMapper;

    @Mock
    private RedisRateLimiter rateLimiter;

    @InjectMocks
    private AnalysisBatchService batchService;

    @Test
    void shouldValidateAndCreateUploadedBatch() {
        MockMultipartFile file = csvFile();
        AnalysisBatchCsvData csvData = new AnalysisBatchCsvData(
                "comments.csv",
                List.of(new AnalysisBatchCommentRow(2, "讲解清晰", 5))
        );
        AnalysisBatchCreateResponse created =
                new AnalysisBatchCreateResponse(30L, "batch-30", 14L, 1);
        given(csvParser.parse(file)).willReturn(csvData);
        given(creationService.create(
                14L,
                11L,
                "comments.csv",
                csvData.rows()
        )).willReturn(created);

        AnalysisBatchCreateResponse response = batchService.upload(
                14L,
                11L,
                UserRole.TEACHER,
                file
        );

        assertThat(response).isEqualTo(created);
        verify(rateLimiter).check(RateLimitPolicy.BATCH_ANALYSIS_UPLOAD, 11L);
        verify(accessService).assertCanManage(14L, 11L, UserRole.TEACHER);
    }

    @Test
    void shouldStopBeforePermissionAndParsingWhenRateLimitIsExceeded() {
        MockMultipartFile file = csvFile();
        willThrow(new RateLimitExceededException("批量分析上传过于频繁，请稍后再试"))
                .given(rateLimiter)
                .check(RateLimitPolicy.BATCH_ANALYSIS_UPLOAD, 11L);

        assertThatThrownBy(() -> batchService.upload(
                14L,
                11L,
                UserRole.TEACHER,
                file
        )).isInstanceOf(RateLimitExceededException.class);

        verifyNoInteractions(accessService, csvParser, creationService);
    }

    @Test
    void shouldReturnProgressForManageableCourse() {
        AnalysisBatch batch = new AnalysisBatch();
        batch.setId(30L);
        batch.setCourseId(14L);
        given(batchMapper.selectById(30L)).willReturn(batch);
        given(progressMapper.selectProgress(30L)).willReturn(progress());

        AnalysisBatchProgressResponse response = batchService.getProgress(
                30L,
                11L,
                UserRole.TEACHER
        );

        assertThat(response.batchId()).isEqualTo(30L);
        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.waitingCount()).isEqualTo(2);
        verify(accessService).assertCanManage(14L, 11L, UserRole.TEACHER);
    }

    @Test
    void shouldRejectMissingBatch() {
        given(batchMapper.selectById(999L)).willReturn(null);

        assertThatThrownBy(() -> batchService.getProgress(
                999L,
                11L,
                UserRole.TEACHER
        ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("批量分析任务不存在");

        verifyNoInteractions(accessService, progressMapper);
    }

    private MockMultipartFile csvFile() {
        return new MockMultipartFile(
                "file",
                "comments.csv",
                "text/csv",
                "content,rating\n讲解清晰,5\n".getBytes()
        );
    }

    private AnalysisBatchProgressAggregate progress() {
        AnalysisBatchProgressAggregate aggregate = new AnalysisBatchProgressAggregate();
        aggregate.setBatchId(30L);
        aggregate.setBatchNo("batch-30");
        aggregate.setCourseId(14L);
        aggregate.setCreatedBy(11L);
        aggregate.setOriginalFilename("comments.csv");
        aggregate.setTotalCount(2);
        aggregate.setWaitingCount(2L);
        aggregate.setProcessingCount(0L);
        aggregate.setRetryingCount(0L);
        aggregate.setSuccessCount(0L);
        aggregate.setFailedCount(0L);
        aggregate.setCreatedAt(LocalDateTime.of(2026, 8, 8, 10, 0));
        return aggregate;
    }
}
