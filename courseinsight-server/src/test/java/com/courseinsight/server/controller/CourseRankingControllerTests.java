package com.courseinsight.server.controller;

import com.courseinsight.server.dto.CoursePopularityRankingItemResponse;
import com.courseinsight.server.dto.CourseRankingQuery;
import com.courseinsight.server.exception.GlobalExceptionHandler;
import com.courseinsight.server.service.CoursePopularityRankingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CourseRankingController.class)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {
        CourseRankingController.class,
        GlobalExceptionHandler.class
})
class CourseRankingControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CoursePopularityRankingService rankingService;

    @Test
    void shouldReturnPopularCourseRanking() throws Exception {
        given(rankingService.getPopular(any(CourseRankingQuery.class)))
                .willReturn(List.of(new CoursePopularityRankingItemResponse(
                        1,
                        14L,
                        "AI-TEST-001",
                        "Java后端开发",
                        "测试教师",
                        6
                )));

        mockMvc.perform(get("/api/course-rankings/popular").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].rank").value(1))
                .andExpect(jsonPath("$.data[0].courseId").value(14))
                .andExpect(jsonPath("$.data[0].courseName").value("Java后端开发"))
                .andExpect(jsonPath("$.data[0].commentCount").value(6));
    }

    @Test
    void shouldRejectRankingLimitAboveMaximum() throws Exception {
        mockMvc.perform(get("/api/course-rankings/popular").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(rankingService);
    }
}
