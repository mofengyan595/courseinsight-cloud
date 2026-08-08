package com.courseinsight.server.service;

import com.courseinsight.server.dto.AnalysisBatchCsvData;
import com.courseinsight.server.exception.InvalidCsvFileException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisBatchCsvParserTests {

    private final AnalysisBatchCsvParser csvParser = new AnalysisBatchCsvParser();

    @Test
    void shouldParseUtf8BomAndQuotedComma() {
        MockMultipartFile file = csvFile(
                "\uFEFFcontent,rating\n"
                        + "\"讲解清楚, 示例丰富\",5\n"
                        + "课程进度有点快,2\n"
        );

        AnalysisBatchCsvData result = csvParser.parse(file);

        assertThat(result.originalFilename()).isEqualTo("comments.csv");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).rowNumber()).isEqualTo(2);
        assertThat(result.rows().get(0).content()).isEqualTo("讲解清楚, 示例丰富");
        assertThat(result.rows().get(0).rating()).isEqualTo(5);
        assertThat(result.rows().get(1).rating()).isEqualTo(2);
    }

    @Test
    void shouldRejectMissingRequiredHeader() {
        MockMultipartFile file = csvFile("text,score\n课程很好,5\n");

        assertThatThrownBy(() -> csvParser.parse(file))
                .isInstanceOf(InvalidCsvFileException.class)
                .hasMessage("CSV 表头必须包含 content 和 rating");
    }

    @Test
    void shouldReportInvalidRatingWithCsvLineNumber() {
        MockMultipartFile file = csvFile("content,rating\n课程很好,6\n");

        assertThatThrownBy(() -> csvParser.parse(file))
                .isInstanceOf(InvalidCsvFileException.class)
                .hasMessage("CSV 第 2 行：rating 必须是 1 到 5 的整数");
    }

    @Test
    void shouldRejectMoreThanMaximumRows() {
        StringBuilder csv = new StringBuilder("content,rating\n");
        for (int row = 0; row <= AnalysisBatchCsvParser.MAX_ROWS; row++) {
            csv.append("评价").append(row).append(",4\n");
        }

        assertThatThrownBy(() -> csvParser.parse(csvFile(csv.toString())))
                .isInstanceOf(InvalidCsvFileException.class)
                .hasMessage("CSV 最多允许 200 条评价");
    }

    private MockMultipartFile csvFile(String content) {
        return new MockMultipartFile(
                "file",
                "C:\\fakepath\\comments.csv",
                "text/csv",
                content.getBytes(StandardCharsets.UTF_8)
        );
    }
}
