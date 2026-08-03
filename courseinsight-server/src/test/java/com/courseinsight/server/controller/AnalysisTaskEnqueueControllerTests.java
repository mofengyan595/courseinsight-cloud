package com.courseinsight.server.controller;

import com.courseinsight.server.dto.AnalysisTaskEnqueueResponse;
import com.courseinsight.server.exception.GlobalExceptionHandler;
import com.courseinsight.server.exception.MessageQueueException;
import com.courseinsight.server.service.AnalysisTaskEnqueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalysisTaskEnqueueController.class)
@ContextConfiguration(classes = {
        AnalysisTaskEnqueueController.class,
        GlobalExceptionHandler.class
})
class AnalysisTaskEnqueueControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalysisTaskEnqueueService enqueueService;

    @Test
    void shouldAcceptTaskForAsyncAnalysis() throws Exception {
        AnalysisTaskEnqueueResponse response =
                new AnalysisTaskEnqueueResponse("event-1", 6L, 13L, "message-1");
        given(enqueueService.enqueue(6L)).willReturn(response);

        mockMvc.perform(post("/api/analysis-tasks/{taskId}/enqueue", 6L))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.eventId").value("event-1"))
                .andExpect(jsonPath("$.data.taskId").value(6L))
                .andExpect(jsonPath("$.data.messageId").value("message-1"));
    }

    @Test
    void shouldReturnServiceUnavailableWhenMessageCannotBeSent() throws Exception {
        given(enqueueService.enqueue(6L))
                .willThrow(new MessageQueueException("RocketMQ 暂时不可用，分析任务未入队"));

        mockMvc.perform(post("/api/analysis-tasks/{taskId}/enqueue", 6L))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("RocketMQ 暂时不可用，分析任务未入队"));
    }
}
