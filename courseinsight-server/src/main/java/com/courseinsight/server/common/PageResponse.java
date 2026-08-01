package com.courseinsight.server.common;

import java.util.List;

public record PageResponse<T>(
        long page,
        long size,
        long total,
        long totalPages,
        List<T> items
) {
}
