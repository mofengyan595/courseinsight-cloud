package com.courseinsight.server.controller;

import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.AnalysisBatchCreateResponse;
import com.courseinsight.server.dto.AnalysisBatchProgressResponse;
import com.courseinsight.server.dto.AnalysisBatchResultItemResponse;
import com.courseinsight.server.dto.AnalysisBatchResultPageQuery;
import com.courseinsight.server.dto.AnalysisBatchRetryResponse;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.GlobalExceptionHandler;
import com.courseinsight.server.exception.InvalidCsvFileException;
import com.courseinsight.server.service.AnalysisBatchRecoveryService;
import com.courseinsight.server.service.AnalysisBatchResultService;
import com.courseinsight.server.service.AnalysisBatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalysisBatchController.class)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {
        AnalysisBatchController.class,
        GlobalExceptionHandler.class
})
class AnalysisBatchControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalysisBatchService batchService;

    @MockitoBean
    private AnalysisBatchResultService resultService;

    @MockitoBean
    private AnalysisBatchRecoveryService recoveryService;

    @Test
    void shouldCreateAnalysisBatchFromCsv() throws Exception {
        MockMultipartFile file = csvFile();
        given(batchService.upload(
                eq(14L),
                eq(11L),
                eq(UserRole.TEACHER),
                any()
        )).willReturn(new AnalysisBatchCreateResponse(
                30L,
                "batch-30",
                14L,
                2
        ));

        mockMvc.perform(multipart("/api/courses/{courseId}/analysis-batches", 14L)
                        .file(file)
                        .principal(teacherAuthentication()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.batchId").value(30))
                .andExpect(jsonPath("$.data.batchNo").value("batch-30"))
                .andExpect(jsonPath("$.data.totalCount").value(2));
    }

    @Test
    void shouldReturnBadRequestForInvalidCsv() throws Exception {
        given(batchService.upload(
                eq(14L),
                eq(11L),
                eq(UserRole.TEACHER),
                any()
        )).willThrow(new InvalidCsvFileException(
                "CSV 表头必须包含 content 和 rating"
        ));

        mockMvc.perform(multipart("/api/courses/{courseId}/analysis-batches", 14L)
                        .file(csvFile())
                        .principal(teacherAuthentication()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message")
                        .value("CSV 表头必须包含 content 和 rating"));
    }

    @Test
    void shouldReturnAnalysisBatchProgress() throws Exception {
        given(batchService.getProgress(30L, 11L, UserRole.TEACHER))
                .willReturn(progressResponse());

        mockMvc.perform(get("/api/analysis-batches/{batchId}", 30L)
                        .principal(teacherAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.waitingCount").value(1))
                .andExpect(jsonPath("$.data.successCount").value(1))
                .andExpect(jsonPath("$.data.completionPercentage").value(50.0));
    }

    @Test
    void shouldReturnPagedAnalysisBatchResults() throws Exception {
        given(resultService.page(
                eq(30L),
                eq(11L),
                eq(UserRole.TEACHER),
                any(AnalysisBatchResultPageQuery.class)
        )).willReturn(new PageResponse<>(
                1,
                20,
                1,
                1,
                List.of(resultItem())
        ));

        mockMvc.perform(get("/api/analysis-batches/{batchId}/results", 30L)
                        .param("page", "1")
                        .param("size", "20")
                        .principal(teacherAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].taskId").value(60))
                .andExpect(jsonPath("$.data.items[0].sentiment").value("positive"))
                .andExpect(jsonPath("$.data.items[0].topics[0]").value("examples"));
    }

    @Test
    void shouldRejectInvalidResultPageSize() throws Exception {
        mockMvc.perform(get("/api/analysis-batches/{batchId}/results", 30L)
                        .param("size", "101")
                        .principal(teacherAuthentication()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldAcceptDeadLetteredBatchRetry() throws Exception {
        given(recoveryService.retryDeadLettered(
                30L,
                11L,
                UserRole.TEACHER
        )).willReturn(new AnalysisBatchRetryResponse(
                30L,
                2,
                LocalDateTime.of(2026, 8, 8, 11, 0)
        ));

        mockMvc.perform(post("/api/analysis-batches/{batchId}/retry-failed", 30L)
                        .principal(teacherAuthentication()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.batchId").value(30))
                .andExpect(jsonPath("$.data.requeuedCount").value(2));
    }

    private MockMultipartFile csvFile() {
        return new MockMultipartFile(
                "file",
                "comments.csv",
                "text/csv",
                "content,rating\n讲解清晰,5\n进度太快,2\n"
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private Authentication teacherAuthentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                "11",
                "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))
        );
    }

    private AnalysisBatchProgressResponse progressResponse() {
        return new AnalysisBatchProgressResponse(
                30L,
                "batch-30",
                14L,
                "comments.csv",
                "PROCESSING",
                2,
                1,
                0,
                0,
                1,
                0,
                new BigDecimal("50.00"),
                LocalDateTime.of(2026, 8, 8, 10, 0),
                null
        );
    }

    private AnalysisBatchResultItemResponse resultItem() {
        return new AnalysisBatchResultItemResponse(
                60L,
                70L,
                "Clear examples",
                5,
                "SUCCESS",
                0,
                null,
                null,
                LocalDateTime.of(2026, 8, 8, 10, 1),
                80L,
                "en",
                "positive",
                new BigDecimal("0.95"),
                "bert",
                "cpu",
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .createArrayNode().add("examples"),
                new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode(),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .createArrayNode().add("clear"),
                false,
                false,
                null,
                "low",
                "llm_api",
                LocalDateTime.of(2026, 8, 8, 10, 1)
        );
    }
}
