package com.courseinsight.server.service;

import com.courseinsight.server.dto.AnalysisBatchCommentRow;
import com.courseinsight.server.dto.AnalysisBatchCsvData;
import com.courseinsight.server.exception.InvalidCsvFileException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AnalysisBatchCsvParser {

    static final long MAX_FILE_SIZE = 2L * 1024 * 1024;
    static final int MAX_ROWS = 200;
    static final int MAX_CONTENT_LENGTH = 2000;

    private static final String CONTENT_HEADER = "content";
    private static final String RATING_HEADER = "rating";

    public AnalysisBatchCsvData parse(MultipartFile file) {
        String originalFilename = validateFile(file);

        try (PushbackReader reader = openUtf8ReaderWithoutBom(file);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreHeaderCase(true)
                     .setIgnoreEmptyLines(true)
                     .setTrim(true)
                     .get()
                     .parse(reader)) {
            validateHeaders(parser);

            List<AnalysisBatchCommentRow> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (rows.size() >= MAX_ROWS) {
                    throw new InvalidCsvFileException("CSV 最多允许 200 条评价");
                }
                rows.add(parseRow(record));
            }
            if (rows.isEmpty()) {
                throw new InvalidCsvFileException("CSV 至少需要包含一条评价");
            }
            return new AnalysisBatchCsvData(originalFilename, rows);
        } catch (InvalidCsvFileException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new InvalidCsvFileException("CSV 文件格式错误或无法读取", exception);
        }
    }

    private String validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidCsvFileException("请选择非空的 CSV 文件");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new InvalidCsvFileException("CSV 文件不能超过 2MB");
        }

        String cleanedPath = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "" : file.getOriginalFilename()
        );
        String filename = StringUtils.getFilename(cleanedPath);
        if (filename == null || filename.isBlank()
                || !filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new InvalidCsvFileException("仅支持 .csv 文件");
        }
        if (filename.length() > 255) {
            throw new InvalidCsvFileException("CSV 文件名不能超过 255 个字符");
        }
        return filename;
    }

    private PushbackReader openUtf8ReaderWithoutBom(MultipartFile file) throws IOException {
        PushbackReader reader = new PushbackReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8),
                1
        );
        int firstCharacter = reader.read();
        if (firstCharacter != 0xFEFF && firstCharacter != -1) {
            reader.unread(firstCharacter);
        }
        return reader;
    }

    private void validateHeaders(CSVParser parser) {
        Set<String> headers = parser.getHeaderMap()
                .keySet()
                .stream()
                .map(header -> header.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (!headers.contains(CONTENT_HEADER) || !headers.contains(RATING_HEADER)) {
            throw new InvalidCsvFileException(
                    "CSV 表头必须包含 content 和 rating"
            );
        }
    }

    private AnalysisBatchCommentRow parseRow(CSVRecord record) {
        long rowNumber = record.getRecordNumber() + 1;
        String content = record.get(CONTENT_HEADER).trim();
        if (content.isEmpty()) {
            throw invalidRow(rowNumber, "content 不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw invalidRow(rowNumber, "content 不能超过 2000 个字符");
        }

        String ratingValue = record.get(RATING_HEADER).trim();
        int rating;
        try {
            rating = Integer.parseInt(ratingValue);
        } catch (NumberFormatException exception) {
            throw invalidRow(rowNumber, "rating 必须是 1 到 5 的整数");
        }
        if (rating < 1 || rating > 5) {
            throw invalidRow(rowNumber, "rating 必须是 1 到 5 的整数");
        }
        return new AnalysisBatchCommentRow(rowNumber, content, rating);
    }

    private InvalidCsvFileException invalidRow(long rowNumber, String message) {
        return new InvalidCsvFileException("CSV 第 " + rowNumber + " 行：" + message);
    }
}
