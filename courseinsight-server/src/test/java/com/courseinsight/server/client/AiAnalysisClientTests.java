package com.courseinsight.server.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiAnalysisClientTests {

    @Test
    void shouldSendAnalyzeRequestAndParseResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AiAnalysisClient client = new AiAnalysisClient(
                builder.baseUrl("http://ai-service:8000").build()
        );

        server.expect(requestTo("http://ai-service:8000/api/v1/analyze"))
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

        AiAnalysisResponse response = client.analyze(
                3L,
                10L,
                "The course is clear and useful.",
                true
        );

        assertThat(response.sentiment()).isEqualTo("positive");
        assertThat(response.sentimentSource()).isEqualTo("bert");
        assertThat(response.topics()).containsExactly("clarity");
        assertThat(response.advice().source()).isEqualTo("llm_api");
        server.verify();
    }
}
