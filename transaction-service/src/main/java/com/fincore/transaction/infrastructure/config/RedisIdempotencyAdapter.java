package com.fincore.transaction.infrastructure.config;

import com.fincore.transaction.domain.port.IdempotencyPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class RedisIdempotencyAdapter implements IdempotencyPort {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyAdapter.class);

    private final RedisTemplate<String, String> redisTemplate;

    public RedisIdempotencyAdapter(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(
                    redisTemplate.opsForValue().get("idempotency:" + key)
            );
        } catch (Exception e) {
            log.warn("Redis unavailable for idempotency read key={}. Falling back to database.", key);
            return Optional.empty();
        }
    }

    @Override
    public void save(String key, String value, long ttlHours) {
        try {
            redisTemplate.opsForValue().set(
                    "idempotency:" + key,
                    value,
                    Duration.ofHours(ttlHours)
            );
        } catch (Exception e) {
            log.warn("Redis unavailable for idempotency write key={}. Database remains source of truth.", key);
        }
    }
}