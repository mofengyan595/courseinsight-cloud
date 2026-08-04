package com.courseinsight.server.controller;

import com.courseinsight.server.common.PageResponse;
import com.courseinsight.server.dto.CommentDetailResponse;
import com.courseinsight.server.dto.CommentPageQuery;
import com.courseinsight.server.exception.GlobalExceptionHandler;
import com.courseinsight.server.exception.RateLimitExceededException;
import com.courseinsight.server.exception.ResourceNotFoundException;
import com.courseinsight.server.service.CommentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommentController.class)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = {CommentController.class, GlobalExceptionHandler.class})
class CommentControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommentService commentService;

    @Test
    void shouldCreateComment() throws Exception {
        given(commentService.create(eq(1L), eq(7L), any())).willReturn(10L);

        mockMvc.perform(post("/api/courses/{courseId}/comments", 1L)
                        .principal(() -> "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "课程讲解清晰，示例很实用",
                                  "rating": 5
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").value(10));
    }

    @Test
    void shouldRejectInvalidComment() throws Exception {
        mockMvc.perform(post("/api/courses/{courseId}/comments", 1L)
                        .principal(() -> "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "   ",
                                  "rating": 6
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(commentService);
    }

    @Test
    void shouldReturnNotFoundWhenCourseDoesNotExist() throws Exception {
        given(commentService.create(eq(999999L), eq(7L), any()))
                .willThrow(new ResourceNotFoundException("课程不存在"));

        mockMvc.perform(post("/api/courses/{courseId}/comments", 999999L)
                        .principal(() -> "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "课程评价",
                                  "rating": 4
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("课程不存在"));
    }

    @Test
    void shouldReturnTooManyRequestsWhenCommentRateLimitIsExceeded() throws Exception {
        given(commentService.create(eq(1L), eq(7L), any()))
                .willThrow(new RateLimitExceededException(
                        "提交评价过于频繁，请稍后再试"
                ));

        mockMvc.perform(post("/api/courses/{courseId}/comments", 1L)
                        .principal(() -> "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "课程评价",
                                  "rating": 4
                                }
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message")
                        .value("提交评价过于频繁，请稍后再试"));
    }

    @Test
    void shouldPageComments() throws Exception {
        CommentDetailResponse comment = new CommentDetailResponse(
                10L,
                1L,
                "课程讲解清晰",
                5,
                true,
                1,
                LocalDateTime.of(2026, 8, 1, 10, 0),
                LocalDateTime.of(2026, 8, 1, 10, 0)
        );
        given(commentService.page(eq(1L), any(CommentPageQuery.class)))
                .willReturn(new PageResponse<>(1, 10, 1, 1, List.of(comment)));

        mockMvc.perform(get("/api/courses/{courseId}/comments", 1L)
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(10))
                .andExpect(jsonPath("$.data.items[0].courseId").value(1))
                .andExpect(jsonPath("$.data.items[0].rating").value(5))
                .andExpect(jsonPath("$.data.items[0].anonymous").value(true))
                .andExpect(jsonPath("$.data.items[0].userId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].username").doesNotExist());
    }

    @Test
    void shouldPageCurrentUsersComments() throws Exception {
        CommentDetailResponse comment = new CommentDetailResponse(
                10L,
                1L,
                "课程讲解清晰",
                5,
                true,
                1,
                LocalDateTime.of(2026, 8, 1, 10, 0),
                LocalDateTime.of(2026, 8, 1, 10, 0)
        );
        given(commentService.pageByUser(eq(7L), any(CommentPageQuery.class)))
                .willReturn(new PageResponse<>(1, 10, 1, 1, List.of(comment)));

        mockMvc.perform(get("/api/comments/me")
                        .principal(() -> "7")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(10))
                .andExpect(jsonPath("$.data.items[0].anonymous").value(true))
                .andExpect(jsonPath("$.data.items[0].userId").doesNotExist());
    }

    @Test
    void shouldDeleteCurrentUsersComment() throws Exception {
        mockMvc.perform(delete("/api/comments/{commentId}", 10L)
                        .principal(() -> "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(commentService).delete(10L, 7L);
    }

    @Test
    void shouldHideWhetherCommentBelongsToAnotherUser() throws Exception {
        willThrow(new ResourceNotFoundException("评价不存在"))
                .given(commentService)
                .delete(10L, 7L);

        mockMvc.perform(delete("/api/comments/{commentId}", 10L)
                        .principal(() -> "7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("评价不存在"));
    }

    @Test
    void shouldRejectInvalidPageParameters() throws Exception {
        mockMvc.perform(get("/api/courses/{courseId}/comments", 1L)
                        .param("page", "0")
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(commentService);
    }
}
