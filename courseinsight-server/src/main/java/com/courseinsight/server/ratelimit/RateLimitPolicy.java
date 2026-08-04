package com.courseinsight.server.ratelimit;

public enum RateLimitPolicy {

    COMMENT_SUBMISSION(
            "comment-submission",
            5,
            60,
            "提交评价过于频繁，请稍后再试"
    ),
    MANUAL_ANALYSIS(
            "manual-analysis",
            10,
            60,
            "AI 分析操作过于频繁，请稍后再试"
    );

    private final String keySegment;
    private final int maxRequests;
    private final int windowSeconds;
    private final String exceededMessage;

    RateLimitPolicy(
            String keySegment,
            int maxRequests,
            int windowSeconds,
            String exceededMessage) {
        this.keySegment = keySegment;
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
        this.exceededMessage = exceededMessage;
    }

    public String keySegment() {
        return keySegment;
    }

    public int maxRequests() {
        return maxRequests;
    }

    public int windowSeconds() {
        return windowSeconds;
    }

    public String exceededMessage() {
        return exceededMessage;
    }
}
