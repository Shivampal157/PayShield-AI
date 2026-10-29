package com.payshield;

import com.payshield.config.AppProperties;
import com.payshield.service.RedisRiskEngine;
import com.payshield.service.RedisRiskEngine.RagCacheLookup;
import com.payshield.service.RedisRiskEngine.VelocityCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class RedisRiskEngineTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private AppProperties properties;
    private RedisRiskEngine redisRiskEngine;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        properties = new AppProperties();
        properties.getRisk().setVelocityMaxAttempts(5);
        properties.getRisk().setVelocityWindowSeconds(60);

        redisRiskEngine = new RedisRiskEngine(redisTemplate, properties, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("Redis Lua ZSET sliding-window velocity allows up to 5 attempts and flags the 6th attempt")
    @SuppressWarnings("unchecked")
    void redisLuaVelocityBoundaryEnforcement() {
        UUID userId = UUID.randomUUID();
        AtomicInteger counter = new AtomicInteger(0);

        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenAnswer(inv -> (long) counter.incrementAndGet());

        for (int i = 1; i <= 5; i++) {
            VelocityCheckResult res = redisRiskEngine.recordAndCheckVelocity(userId);
            assertThat(res.attemptsInWindow()).isEqualTo(i);
            assertThat(res.exceeded()).isFalse();
            assertThat(res.redisBacked()).isTrue();
            assertThat(res.engineMode()).isEqualTo("REDIS_LUA_ZSET");
        }

        VelocityCheckResult sixth = redisRiskEngine.recordAndCheckVelocity(userId);
        assertThat(sixth.attemptsInWindow()).isEqualTo(6);
        assertThat(sixth.exceeded()).isTrue();
        assertThat(sixth.redisBacked()).isTrue();
    }

    @Test
    @DisplayName("When Redis is unavailable, velocity limiter degrades gracefully to concurrency-safe fallback")
    @SuppressWarnings("unchecked")
    void fallbackVelocityUnderConcurrentLoadWhenRedisDown() throws Exception {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("Redis connection refused"));

        UUID userId = UUID.randomUUID();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger exceededCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    VelocityCheckResult res = redisRiskEngine.recordAndCheckVelocity(userId);
                    if (res.exceeded()) {
                        exceededCount.incrementAndGet();
                    } else {
                        allowedCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        doneGate.await();
        pool.shutdown();

        // Exactly 5 attempts must be within the limit and 5 must exceed the limit
        assertThat(allowedCount.get()).isEqualTo(5);
        assertThat(exceededCount.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("RAG report cache tracks hits/misses, uses SHA-256 keys, and invalidates on context change")
    void ragCacheHitMissAndContextInvalidation() {
        redisRiskEngine.setForceUnavailableForTest(true); // Test deterministic fallback cache store
        UUID txId = UUID.randomUUID();

        String sigPending = redisRiskEngine.computeRagCacheSignature(
                txId, "FLAGGED_FRAUD", 0.89, null, "2.4.0", "OFFLINE");
        assertThat(sigPending).hasSize(64); // 256-bit SHA-256 hex

        RagCacheLookup miss = redisRiskEngine.getCachedRagReport(sigPending);
        assertThat(miss.hit()).isFalse();
        assertThat(redisRiskEngine.getRagCacheMisses()).isEqualTo(1);

        redisRiskEngine.putCachedRagReport(txId, sigPending, "{\"status\":\"cached\"}");

        RagCacheLookup hit = redisRiskEngine.getCachedRagReport(sigPending);
        assertThat(hit.hit()).isTrue();
        assertThat(hit.payloadJson()).isEqualTo("{\"status\":\"cached\"}");
        assertThat(redisRiskEngine.getRagCacheHits()).isEqualTo(1);

        // When analyst approves transaction, signature changes AND explicit invalidation clears previous entry
        redisRiskEngine.invalidateRagCacheForTransaction(txId);
        RagCacheLookup afterInvalidate = redisRiskEngine.getCachedRagReport(sigPending);
        assertThat(afterInvalidate.hit()).isFalse();
    }
}
