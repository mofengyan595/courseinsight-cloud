package com.courseinsight.server.controller;

import com.courseinsight.server.dto.AnalysisExecutionResponse;
import com.courseinsight.server.exception.AiServiceException;
import com.courseinsight.server.exception.GlobalExceptionHandler;
import com.courseinsight.server.service.AnalysisExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalysisExecutionController.class)
@ContextConfiguration(classes = {
        AnalysisExecutionController.class,
        GlobalExceptionHandler.class
})
class AnalysisExecutionControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalysisExecutionService analysisExecutionService;

    @Test
    void shouldExecuteAnalysisTask() throws Exception {
        AnalysisExecutionResponse response = new AnalysisExecutionResponse(
                20L,
                3L,
                10L,
                "SUCCESS",
                "en",
                "positive",
                new BigDecimal("0.98123"),
                "low",
                "llm_api"
        );
        given(analysisExecutionService.execute(3L)).willReturn(response);

        mockMvc.perform(post("/api/analysis-tasks/{taskId}/execute", 3L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value(3))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.sentiment").value("positive"))
                .andExpect(jsonPath("$.data.adviceSource").value("llm_api"));
    }

    @Test
    void shouldReturnBadGatewayWhenAiServiceFails() throws Exception {
        given(analysisExecutionService.execute(3L))
                .willThrow(new AiServiceException("AI 服务调用失败"));

        mockMvc.perform(post("/api/analysis-tasks/{taskId}/execute", 3L))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(502))
                .andExpect(jsonPath("$.message").value("AI 服务调用失败"));
    }
}
