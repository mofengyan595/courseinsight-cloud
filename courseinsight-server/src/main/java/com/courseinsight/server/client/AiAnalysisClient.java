package com.courseinsight.server.client;

import com.courseinsight.server.exception.NonRetryableAiServiceException;
import com.courseinsight.server.exception.RetryableAiServiceException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

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
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (exception.getStatusCode().is5xxServerError()
                    || status == 408
                    || status == 429) {
                throw new RetryableAiServiceException(
                        "AI 服务调用失败",
                        exception
                );
            }
            throw new NonRetryableAiServiceException(
                    "AI 服务调用失败",
                    exception
            );
        } catch (ResourceAccessException exception) {
            throw new RetryableAiServiceException(
                    "AI 服务调用失败",
                    exception
            );
        } catch (RestClientException exception) {
            // A successful HTTP response that cannot be decoded violates the
            // response contract; replaying the same request will not repair it.
            throw new NonRetryableAiServiceException(
                    "AI 服务调用失败",
                    exception
            );
        }
    }

    private void validateResponse(
            Long taskId,
            Long commentId,
            AiAnalysisResponse response) {
        if (response == null
                || !taskId.equals(response.taskId())
                || !commentId.equals(response.commentId())) {
            throw new NonRetryableAiServiceException(
                    "AI service response does not match the analysis task"
            );
        }
    }
}
