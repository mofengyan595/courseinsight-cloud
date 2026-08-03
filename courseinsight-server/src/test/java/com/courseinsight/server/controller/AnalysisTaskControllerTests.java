package com.courseinsight.server.controller;

import com.courseinsight.server.dto.AnalysisTaskDetailResponse;
import com.courseinsight.server.exception.GlobalExceptionHandler;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.service.AnalysisTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalysisTaskController.class)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {AnalysisTaskController.class, GlobalExceptionHandler.class})
class AnalysisTaskControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalysisTaskService analysisTaskService;

    @Test
    void shouldGetTaskByCommentId() throws Exception {
        AnalysisTaskDetailResponse response = new AnalysisTaskDetailResponse(
                3L,
                "1234567890abcdef1234567890abcdef",
                10L,
                1L,
                "WAITING",
                0,
                null,
                null,
                null,
                null,
                null,
                null
        );
        given(analysisTaskService.getByCommentId(10L)).willReturn(response);

        mockMvc.perform(get("/api/comments/{commentId}/analysis-task", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value(3))
                .andExpect(jsonPath("$.data.commentId").value(10))
                .andExpect(jsonPath("$.data.courseId").value(1))
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.retryCount").value(0))
                .andExpect(jsonPath("$.data.deadLetteredAt").doesNotExist());
    }

    @Test
    void shouldReturnNotFoundWhenTaskDoesNotExist() throws Exception {
        given(analysisTaskService.getByCommentId(999999L))
                .willThrow(new ResourceNotFoundException("分析任务不存在"));

        mockMvc.perform(get("/api/comments/{commentId}/analysis-task", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("分析任务不存在"));
    }
}
