package com.courseinsight.server.cache;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

final class CacheTtl {

    private CacheTtl() {
    }

    // 只缩短原 TTL，避免扩大删除失败时的旧数据存活窗口。
    static Duration jitter(Duration maximum) {
        long upper = maximum.toMillis();
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(upper * 4 / 5, upper + 1));
    }
}
