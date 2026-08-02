package com.courseinsight.server.client;

import com.courseinsight.server.exception.AiServiceException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class AiAnalysisClient {

    private final RestClient restClient;

    public AiAnalysisClient(@Qualifier("aiRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public AiAnalysisResponse analyze(
            Long taskId,
            Long commentId,
            String text,
            boolean includeAdvice) {
        AiAnalysisRequest request = new AiAnalysisRequest(
                taskId,
                commentId,
                text,
                includeAdvice
        );

        try {
            AiAnalysisResponse response = restClient.post()
                    .uri("/api/v1/analyze")
                    .body(request)
                    .retrieve()
                    .body(AiAnalysisResponse.class);

            validateResponse(taskId, commentId, response);
            return response;
        } catch (RestClientException exception) {
            throw new AiServiceException("AI 服务调用失败", exception);
        }
    }

    private void validateResponse(
            Long taskId,
            Long commentId,
            AiAnalysisResponse response) {
        if (response == null
                || !taskId.equals(response.taskId())
                || !commentId.equals(response.commentId())) {
            throw new AiServiceException("AI 服务返回结果与分析任务不匹配");
        }
    }
}
