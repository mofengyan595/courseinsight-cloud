package com.courseinsight.server.controller;

import com.courseinsight.server.dto.AnalysisTaskDetailResponse;
import com.courseinsight.server.entity.UserRole;
import com.courseinsight.server.exception.GlobalExceptionHandler;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.service.AnalysisTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
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
    void shouldPassAuthenticatedUserToObjectAuthorization() throws Exception {
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
        given(analysisTaskService.getByCommentId(
                10L,
                20L,
                UserRole.STUDENT
        )).willReturn(response);

        mockMvc.perform(get("/api/comments/{commentId}/analysis-task", 10L)
                        .principal(student()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(3));
    }

    @Test
    void shouldReturnNotFoundWhenTaskDoesNotExist() throws Exception {
        given(analysisTaskService.getByCommentId(
                999999L,
                20L,
                UserRole.STUDENT
        )).willThrow(new ResourceNotFoundException("Analysis task does not exist"));

        mockMvc.perform(get("/api/comments/{commentId}/analysis-task", 999999L)
                        .principal(student()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    private TestingAuthenticationToken student() {
        return new TestingAuthenticationToken("20", null, "ROLE_STUDENT");
    }
}
