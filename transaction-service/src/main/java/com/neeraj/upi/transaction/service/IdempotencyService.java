package com.neeraj.upi.transaction.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Manages idempotency keys in Redis.
 *
 * Flow:
 *   1. Client sends a payment with a unique requestId.
 *   2. Before processing, we check Redis: "Have we seen this requestId?"
 *   3. If YES  → return the cached txnId (same response as first call).
 *   4. If NO   → process payment, then store requestId → txnId in Redis with TTL.
 *
 * This prevents double-charges when the network drops and the client retries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private static final String KEY_PREFIX = "idempotency:";

    private final StringRedisTemplate redisTemplate;

    @Value("${idempotency.ttl-seconds:86400}")
    private long ttlSeconds;

    public Optional<String> getExistingResult(String requestId) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + requestId);
        return Optional.ofNullable(value);
    }

    public void storeResult(String requestId, String txnId) {
        redisTemplate.opsForValue().set(KEY_PREFIX + requestId, txnId, Duration.ofSeconds(ttlSeconds));
    }
}
