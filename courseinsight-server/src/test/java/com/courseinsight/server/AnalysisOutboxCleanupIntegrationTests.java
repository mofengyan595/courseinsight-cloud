package com.courseinsight.server;

import com.courseinsight.server.mapper.AnalysisOutboxEventMapper;
import com.courseinsight.server.message.AnalysisOutboxCleanup;
import com.courseinsight.server.testsupport.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@MySqlIntegrationTest
@Transactional
@TestPropertySource(properties = {
        "courseinsight.outbox.cleanup.enabled=true",
        "courseinsight.outbox.cleanup.batch-size=2"
})
class AnalysisOutboxCleanupIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AnalysisOutboxEventMapper mapper;

    @Autowired
    private AnalysisOutboxCleanup cleanup;

    @Test
    void cleanupShouldBeBoundedAndPreserveEveryUnsentOrRecentEvent() {
        List<Long> expired = List.of(insert("SENT", 40), insert("SENT", 40), insert("SENT", 40));
        List<Long> protectedIds = new ArrayList<>();
        for (String status : List.of("PENDING", "PUBLISHING", "FAILED")) {
            protectedIds.add(insert(status, 40));
        }
        protectedIds.add(insert("SENT", 1));
        protectedIds.add(insert("SENT", null));
        assertThat(cleanup.cleanupSent()).isEqualTo(2);
        assertThat(expired.stream().filter(this::exists).count()).isEqualTo(1);
        assertThat(protectedIds).allMatch(this::exists);
        assertThat(cleanup.cleanupSent()).isEqualTo(1);
        assertThat(cleanup.cleanupSent()).isZero();
        assertThat(protectedIds).allMatch(this::exists);
    }

    @Test
    void retentionIndexShouldMatchCleanupFilterAndOrder() {
        List<String> columns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'analysis_outbox_event'
                  AND index_name = 'idx_analysis_outbox_sent_retention'
                ORDER BY seq_in_index
                """, String.class);
        assertThat(columns).containsExactly("status", "sent_at", "id");
    }

    private Long insert(String status, Integer daysAgo) {
        String eventId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO analysis_outbox_event
                    (event_id, task_id, comment_id, event_type, status, sent_at)
                VALUES (?, 900000, 900000, 'COMMENT_ANALYSIS_CREATED', ?,
                    TIMESTAMPADD(DAY, -?, CURRENT_TIMESTAMP(3)))
                """, eventId, status, daysAgo);
        return jdbc.queryForObject("SELECT id FROM analysis_outbox_event WHERE event_id = ?", Long.class, eventId);
    }

    private boolean exists(Long id) {
        return mapper.selectById(id) != null;
    }
}
