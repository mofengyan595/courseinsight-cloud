package com.courseinsight.server.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisBatchResultItemResponseTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldExposeStoredJsonAsJsonValuesInsteadOfEscapedStrings() {
        AnalysisBatchResultRow row = new AnalysisBatchResultRow();
        row.setTaskId(10L);
        row.setTopicsJson("[\"pace\",\"examples\"]");
        row.setAdviceJson("{\"summary\":\"slow down\"}");

        AnalysisBatchResultItemResponse response =
                AnalysisBatchResultItemResponse.from(row, objectMapper);

        assertThat(response.topics().isArray()).isTrue();
        assertThat(response.topics().get(0).asText()).isEqualTo("pace");
        assertThat(response.advice().get("summary").asText())
                .isEqualTo("slow down");
    }
}
