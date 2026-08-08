package com.courseinsight.server;

import com.courseinsight.server.client.AiAnalysisClient;
import com.courseinsight.server.client.AiAnalysisResponse;
import com.courseinsight.server.dto.AnalysisExecutionResponse;
import com.courseinsight.server.exception.AiServiceException;
import com.courseinsight.server.service.AnalysisExecutionService;
import com.courseinsight.server.service.AnalysisTaskDeadLetterService;
import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@MySqlIntegrationTest
class AnalysisExecutionIntegrationTests {

    @Autowired
    private AnalysisExecutionService analysisExecutionService;

    @Autowired
    private AnalysisTaskDeadLetterService deadLetterService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AiAnalysisClient aiAnalysisClient;

    @Test
    void shouldPersistResultAndMarkTaskSuccess() {
        String commentText = "The course is clear and useful.";
        TestData testData = createWaitingTask(commentText);

        try {
            given(aiAnalysisClient.analyze(
                    testData.taskId(),
                    testData.commentId(),
                    commentText,
                    true
            )).willReturn(createAiResponse(testData.taskId(), testData.commentId()));

            AnalysisExecutionResponse response = analysisExecutionService.execute(testData.taskId());

            assertThat(response.status()).isEqualTo("SUCCESS");
            assertThat(response.sentiment()).isEqualTo("positive");
            assertThat(response.adviceSource()).isEqualTo("llm_api");

            String taskStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM analysis_task WHERE id = ?",
                    String.class,
                    testData.taskId()
            );
            assertThat(taskStatus).isEqualTo("SUCCESS");

            Integer resultCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM analysis_result WHERE task_id = ?",
                    Integer.class,
                    testData.taskId()
            );
            assertThat(resultCount).isEqualTo(1);

            String topicsJson = jdbcTemplate.queryForObject(
                    "SELECT topics_json FROM analysis_result WHERE task_id = ?",
                    String.class,
                    testData.taskId()
            );
            assertThat(topicsJson).contains("clarity");
        } finally {
            deleteTestData(testData);
        }
    }

    @Test
    void shouldMarkTaskFailedWhenAiServiceFails() {
        String commentText = "The explanation is too fast.";
        TestData testData = createWaitingTask(commentText);

        try {
            given(aiAnalysisClient.analyze(
                    testData.taskId(),
                    testData.commentId(),
                    commentText,
                    true
            )).willThrow(new AiServiceException("AI 服务调用失败"));

            assertThatThrownBy(() -> analysisExecutionService.execute(testData.taskId()))
                    .isInstanceOf(AiServiceException.class)
                    .hasMessage("AI 服务调用失败");

            String taskStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM analysis_task WHERE id = ?",
                    String.class,
                    testData.taskId()
            );
            Integer retryCount = jdbcTemplate.queryForObject(
                    "SELECT retry_count FROM analysis_task WHERE id = ?",
                    Integer.class,
                    testData.taskId()
            );
            String failureReason = jdbcTemplate.queryForObject(
                    "SELECT failure_reason FROM analysis_task WHERE id = ?",
                    String.class,
                    testData.taskId()
            );

            assertThat(taskStatus).isEqualTo("FAILED");
            assertThat(retryCount).isEqualTo(1);
            assertThat(failureReason).isEqualTo("AI 服务调用失败");
        } finally {
            deleteTestData(testData);
        }
    }

    @Test
    void shouldReturnExistingResultWhenTaskIsConsumedAgain() {
        String commentText = "The course is clear and useful.";
        TestData testData = createWaitingTask(commentText);

        try {
            given(aiAnalysisClient.analyze(
                    testData.taskId(),
                    testData.commentId(),
                    commentText,
                    true
            )).willReturn(createAiResponse(testData.taskId(), testData.commentId()));

            AnalysisExecutionResponse firstResponse =
                    analysisExecutionService.execute(testData.taskId());
            AnalysisExecutionResponse duplicateResponse =
                    analysisExecutionService.execute(testData.taskId());

            assertThat(duplicateResponse.resultId()).isEqualTo(firstResponse.resultId());
            assertThat(duplicateResponse.status()).isEqualTo("SUCCESS");
            then(aiAnalysisClient).should(times(1)).analyze(
                    testData.taskId(),
                    testData.commentId(),
                    commentText,
                    true
            );

            Integer resultCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM analysis_result WHERE task_id = ?",
                    Integer.class,
                    testData.taskId()
            );
            assertThat(resultCount).isEqualTo(1);
        } finally {
            deleteTestData(testData);
        }
    }

    @Test
    void shouldClearDeadLetterMarkerWhenTaskIsRetriedSuccessfully() {
        String commentText = "The explanation needs more examples.";
        TestData testData = createWaitingTask(commentText);

        try {
            String eventId = createSentOutboxEvent(testData);
            assertThat(deadLetterService.markDeadLettered(
                    testData.taskId(),
                    eventId
            )).isTrue();

            given(aiAnalysisClient.analyze(
                    testData.taskId(),
                    testData.commentId(),
                    commentText,
                    true
            )).willReturn(createAiResponse(testData.taskId(), testData.commentId()));

            AnalysisExecutionResponse response = analysisExecutionService.execute(testData.taskId());

            assertThat(response.status()).isEqualTo("SUCCESS");
            Integer deadLetterMarkerCount = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM analysis_task
                    WHERE id = ? AND dead_lettered_at IS NOT NULL
                    """,
                    Integer.class,
                    testData.taskId()
            );
            assertThat(deadLetterMarkerCount).isZero();
        } finally {
            deleteTestData(testData);
        }
    }

    private TestData createWaitingTask(String commentText) {
        String courseCode = "AI" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12);
        jdbcTemplate.update(
                """
                INSERT INTO course (code, name, teacher_name, description, status)
                VALUES (?, ?, ?, ?, 1)
                """,
                courseCode,
                "AI integration test course",
                "Test teacher",
                "Verifies analysis persistence"
        );
        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM course WHERE code = ?",
                Long.class,
                courseCode
        );

        jdbcTemplate.update(
                """
                INSERT INTO course_comment (course_id, content, rating, status)
                VALUES (?, ?, ?, 1)
                """,
                courseId,
                commentText,
                5
        );
        Long commentId = jdbcTemplate.queryForObject(
                """
                SELECT id FROM course_comment
                WHERE course_id = ?
                ORDER BY id DESC LIMIT 1
                """,
                Long.class,
                courseId
        );

        String taskNo = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update(
                """
                INSERT INTO analysis_task
                    (task_no, comment_id, course_id, status, retry_count)
                VALUES (?, ?, ?, 'WAITING', 0)
                """,
                taskNo,
                commentId,
                courseId
        );
        Long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM analysis_task WHERE task_no = ?",
                Long.class,
                taskNo
        );
        return new TestData(courseId, commentId, taskId);
    }

    private void deleteTestData(TestData testData) {
        jdbcTemplate.update(
                "DELETE FROM analysis_outbox_event WHERE task_id = ?",
                testData.taskId()
        );
        jdbcTemplate.update(
                "DELETE FROM analysis_result WHERE task_id = ?",
                testData.taskId()
        );
        jdbcTemplate.update(
                "DELETE FROM analysis_task WHERE id = ?",
                testData.taskId()
        );
        jdbcTemplate.update(
                "DELETE FROM course_comment WHERE id = ?",
                testData.commentId()
        );
        jdbcTemplate.update(
                "DELETE FROM course WHERE id = ?",
                testData.courseId()
        );
    }

    private String createSentOutboxEvent(TestData testData) {
        String eventId = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update(
                """
                INSERT INTO analysis_outbox_event
                    (event_id, task_id, comment_id, event_type, status,
                     retry_count, next_retry_at, sent_at)
                VALUES (?, ?, ?, 'COMMENT_ANALYSIS_CREATED', 'SENT',
                        0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """,
                eventId,
                testData.taskId(),
                testData.commentId()
        );
        return eventId;
    }

    private AiAnalysisResponse createAiResponse(Long taskId, Long commentId) {
        AiAnalysisResponse.TopicEvidence evidence = new AiAnalysisResponse.TopicEvidence(
                "clarity",
                List.of("clear"),
                "The course is clear"
        );
        AiAnalysisResponse.AdviceSuggestion suggestion =
                new AiAnalysisResponse.AdviceSuggestion(
                        "clarity",
                        "Keep using concrete examples.",
                        "The course is clear",
                        "maintain"
                );
        AiAnalysisResponse.ReviewAdvice advice = new AiAnalysisResponse.ReviewAdvice(
                "Keep the clear structure.",
                List.of(),
                List.of(suggestion),
                "low",
                "llm_api",
                "en",
                null
        );

        return new AiAnalysisResponse(
                taskId,
                commentId,
                "en",
                "positive",
                new BigDecimal("0.98123"),
                "bert",
                "cpu",
                List.of("clarity"),
                List.of(evidence),
                List.of("course", "clear"),
                false,
                false,
                advice
        );
    }

    private record TestData(Long courseId, Long commentId, Long taskId) {
    }
}
