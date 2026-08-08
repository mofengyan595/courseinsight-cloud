package com.courseinsight.server.client;

import com.courseinsight.server.exception.NonRetryableAiServiceException;
import com.courseinsight.server.exception.RetryableAiServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiAnalysisFailureClassificationTests {

    @Test
    void shouldClassifyPermanent4xxAsNonRetryable() {
        TestClient fixture = createClient();
        fixture.server().expect(requestTo("http://ai/api/v1/analyze"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> analyze(fixture.client()))
                .isInstanceOf(NonRetryableAiServiceException.class);
    }

    @Test
    void shouldClassify5xxAsRetryable() {
        TestClient fixture = createClient();
        fixture.server().expect(requestTo("http://ai/api/v1/analyze"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> analyze(fixture.client()))
                .isInstanceOf(RetryableAiServiceException.class);
    }

    @Test
    void shouldClassifyMalformedSuccessBodyAsNonRetryableContractFailure() {
        TestClient fixture = createClient();
        fixture.server().expect(requestTo("http://ai/api/v1/analyze"))
                .andRespond(withSuccess("{not-json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> analyze(fixture.client()))
                .isInstanceOf(NonRetryableAiServiceException.class);
    }

    @Test
    void shouldClassifyTimeoutAsRetryable() {
        TestClient fixture = createClient();
        fixture.server().expect(requestTo("http://ai/api/v1/analyze"))
                .andRespond(request -> {
                    throw new SocketTimeoutException("timeout");
                });

        assertThatThrownBy(() -> analyze(fixture.client()))
                .isInstanceOf(RetryableAiServiceException.class);
    }

    @Test
    void shouldClassifyConnectionFailureAsRetryable() {
        TestClient fixture = createClient();
        fixture.server().expect(requestTo("http://ai/api/v1/analyze"))
                .andRespond(request -> {
                    throw new ConnectException("refused");
                });

        assertThatThrownBy(() -> analyze(fixture.client()))
                .isInstanceOf(RetryableAiServiceException.class);
    }

    private TestClient createClient() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new TestClient(
                new AiAnalysisClient(builder.baseUrl("http://ai").build()),
                server
        );
    }

    private void analyze(AiAnalysisClient client) {
        client.analyze(3L, 10L, "test", true);
    }

    private record TestClient(
            AiAnalysisClient client,
            MockRestServiceServer server) {
    }
}
