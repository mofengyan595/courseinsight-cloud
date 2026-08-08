package com.courseinsight.server.dto;

import java.util.List;

public record AnalysisBatchCsvData(
        String originalFilename,
        List<AnalysisBatchCommentRow> rows) {

    public AnalysisBatchCsvData {
        rows = List.copyOf(rows);
    }
}
