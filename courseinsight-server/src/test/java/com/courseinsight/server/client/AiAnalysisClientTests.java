package com.courseinsight.server.client;

import com.courseinsight.server.exception.AiServiceException;
import com.courseinsight.server.metrics.CourseInsightMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiAnalysisClientTests {

    @Test
    void shouldSendAnalyzeRequestAndParseResponse() {
        TestClient fixture = createClient();

        fixture.server().expect(requestTo("http://ai-service:8000/api/v1/analyze"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(
                        """
                        {
                          "taskId": 3,
                          "commentId": 10,
                          "text": "The course is clear and useful.",
                          "includeAdvice": true
                        }
                        """
                ))
                .andRespond(withSuccess(
                        """
                        {
                          "taskId": 3,
                          "commentId": 10,
                          "language": "en",
                          "sentiment": "positive",
                          "confidence": 0.98123,
                          "sentimentSource": "bert",
                          "sentimentDevice": "cpu",
                          "topics": ["clarity"],
                          "topicEvidence": [{
                            "aspect": "clarity",
                            "keywords": ["clear"],
                            "evidence": "The course is clear"
                          }],
                          "keywords": ["course", "clear"],
                          "longTextHandled": false,
                          "longTextTruncated": false,
                          "advice": {
                            "summary": "Keep the clear structure.",
                            "problems": [],
                            "suggestions": [{
                              "aspect": "clarity",
                              "suggestion": "Keep using examples.",
                              "evidence": "The course is clear",
                              "actionType": "maintain"
                            }],
                            "riskLevel": "low",
                            "source": "llm_api",
                            "language": "en",
                            "fallbackReason": null
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        AiAnalysisResponse response = fixture.client().analyze(
                3L,
                10L,
                "The course is clear and useful.",
                true
        );

        assertThat(response.sentiment()).isEqualTo("positive");
        assertThat(response.sentimentSource()).isEqualTo("bert");
        assertThat(response.topics()).containsExactly("clarity");
        assertThat(response.advice().source()).isEqualTo("llm_api");
        fixture.server().verify();
    }

    @Test
    void shouldTranslateClientError() {
        TestClient fixture = createClient();
        fixture.server().expect(requestTo("http://ai-service:8000/api/v1/analyze"))
                .andRespond(withBadRequest());

        assertAiServiceFailure(fixture.client());
        fixture.server().verify();
    }

    @Test
    void shouldTranslateServerError() {
        TestClient fixture = createClient();
        fixture.server().expect(requestTo("http://ai-service:8000/api/v1/analyze"))
                .andRespond(withServerError());

        assertAiServiceFailure(fixture.client());
        fixture.server().verify();
    }

    @Test
    void shouldRejectMalformedJsonResponse() {
        TestClient fixture = createClient();
        fixture.server().expect(requestTo("http://ai-service:8000/api/v1/analyze"))
                .andRespond(withSuccess("{not-json", MediaType.APPLICATION_JSON));

        assertAiServiceFailure(fixture.client());
        fixture.server().verify();
    }

    @Test
    void shouldTranslateTimeout() {
        TestClient fixture = createClient();
        fixture.server().expect(requestTo("http://ai-service:8000/api/v1/analyze"))
                .andRespond(request -> {
                    throw new SocketTimeoutException("simulated timeout");
                });

        assertAiServiceFailure(fixture.client());
        fixture.server().verify();
    }

    @Test
    void shouldTranslateConnectionFailure() {
        TestClient fixture = createClient();
        fixture.server().expect(requestTo("http://ai-service:8000/api/v1/analyze"))
                .andRespond(request -> {
                    throw new ConnectException("simulated connection refusal");
                });

        assertAiServiceFailure(fixture.client());
        fixture.server().verify();
    }

    private TestClient createClient() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiAnalysisClient client = new AiAnalysisClient(
                builder.baseUrl("http://ai-service:8000").build(),
                new CourseInsightMetrics(new SimpleMeterRegistry())
        );
        return new TestClient(client, server);
    }

    private void assertAiServiceFailure(AiAnalysisClient client) {
        assertThatThrownBy(() -> client.analyze(3L, 10L, "test", true))
                .isInstanceOf(AiServiceException.class)
                .hasMessage("AI 服务调用失败");
    }

    private record TestClient(
            AiAnalysisClient client,
            MockRestServiceServer server) {
    }
}
