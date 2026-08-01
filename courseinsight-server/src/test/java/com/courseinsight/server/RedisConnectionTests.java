package com.courseinsight.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class RedisConnectionTests {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void connectsToRedis() {
        String key = "test:redis:" + UUID.randomUUID();
        try {
            redisTemplate.opsForValue().set(key, "ok", Duration.ofSeconds(30));
            assertEquals("ok", redisTemplate.opsForValue().get(key));
        } finally {
            redisTemplate.delete(key);
        }
    }
}
