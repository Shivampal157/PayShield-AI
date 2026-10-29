package com.payshield.service;

import com.payshield.config.AppProperties;
import com.payshield.util.HashUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RedisRiskEngine {

    private static final Logger log = LoggerFactory.getLogger(RedisRiskEngine.class);

    /**
     * Atomic Lua script for sliding-window transaction velocity tracking in Redis ZSET:
     * KEYS[1] = sorted set key (e.g. payshield:velocity:{userId})
     * ARGV[1] = windowStartMs (nowMs - windowMs)
     * ARGV[2] = nowMs (score)
     * ARGV[3] = unique member (nowMs:uuid)
     * ARGV[4] = ttlSeconds
     * Returns current attempt count within the rolling window after recording this attempt.
     */
    private static final String SLIDING_WINDOW_LUA = """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
            local count = redis.call('ZCARD', KEYS[1])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
            return count
            """;

    private final StringRedisTemplate redisTemplate;
    private final AppProperties properties;
    private final DefaultRedisScript<Long> velocityScript;
    private final Counter ragCacheHitCounter;
    private final Counter ragCacheMissCounter;

    private final AtomicLong ragCacheHits = new AtomicLong(0);
    private final AtomicLong ragCacheMisses = new AtomicLong(0);
    private final AtomicBoolean lastRedisHealthy = new AtomicBoolean(true);
    private volatile boolean forceUnavailableForTest = false;

    // Concurrency-safe in-memory fallback structures used ONLY when Redis is unreachable
    private final Map<String, Deque<Long>> fallbackVelocityWindows = new ConcurrentHashMap<>();
    private final Map<String, FallbackCacheEntry> fallbackRagCache = new ConcurrentHashMap<>();
    private final Map<String, Long> fallbackIdempotencyLocks = new ConcurrentHashMap<>();

    private record FallbackCacheEntry(String jsonPayload, Instant expiresAt) {
    }

    public record VelocityCheckResult(
            int attemptsInWindow,
            int maxAllowedAttempts,
            int windowSeconds,
            boolean exceeded,
            boolean redisBacked,
            String engineMode
    ) {
    }

    public record RagCacheLookup(
            boolean hit,
            String cacheKeySha256,
            String payloadJson,
            String cacheBackend
    ) {
    }

    public RedisRiskEngine(StringRedisTemplate redisTemplate,
                           AppProperties properties,
                           MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.velocityScript = new DefaultRedisScript<>(SLIDING_WINDOW_LUA, Long.class);
        this.ragCacheHitCounter = Counter.builder("payshield.rag.cache.hits")
                .description("Number of RAG dispute audit report cache hits")
                .register(meterRegistry);
        this.ragCacheMissCounter = Counter.builder("payshield.rag.cache.misses")
                .description("Number of RAG dispute audit report cache misses")
                .register(meterRegistry);
    }

    /**
     * Records a transaction attempt in the user's rolling 60-second sliding window
     * and evaluates whether the velocity threshold (> 5 attempts in 60s) is exceeded.
     */
    public VelocityCheckResult recordAndCheckVelocity(UUID userId) {
        int maxAttempts = properties.getRisk().getVelocityMaxAttempts();
        int windowSeconds = properties.getRisk().getVelocityWindowSeconds();
        long nowMs = System.currentTimeMillis();
        long windowStartMs = nowMs - (windowSeconds * 1000L);
        String key = "payshield:velocity:" + userId;
        String member = nowMs + ":" + UUID.randomUUID();

        if (!forceUnavailableForTest) {
            try {
                Long count = redisTemplate.execute(
                        velocityScript,
                        List.of(key),
                        String.valueOf(windowStartMs),
                        String.valueOf(nowMs),
                        member,
                        String.valueOf(windowSeconds + 10)
                );
                if (count != null) {
                    lastRedisHealthy.set(true);
                    int attempts = count.intValue();
                    return new VelocityCheckResult(
                            attempts,
                            maxAttempts,
                            windowSeconds,
                            attempts > maxAttempts,
                            true,
                            "REDIS_LUA_ZSET"
                    );
                }
            } catch (Exception ex) {
                lastRedisHealthy.set(false);
                log.debug("Redis unavailable during velocity check for userId={}; using concurrency-safe local fallback: {}",
                        userId, ex.getMessage());
            }
        } else {
            lastRedisHealthy.set(false);
        }

        // Concurrency-safe deterministic fallback when Redis is unavailable
        Deque<Long> deque = fallbackVelocityWindows.computeIfAbsent(key, k -> new ArrayDeque<>());
        int attempts;
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() <= windowStartMs) {
                deque.pollFirst();
            }
            deque.addLast(nowMs);
            attempts = deque.size();
        }
        return new VelocityCheckResult(
                attempts,
                maxAttempts,
                windowSeconds,
                attempts > maxAttempts,
                false,
                "LOCAL_CONCURRENT_FALLBACK"
        );
    }

    /**
     * Fast-path atomic idempotency lock coordination using Redis SETNX with TTL.
     * Note: This is an optimization; durable PostgreSQL unique constraints and payload hash verification
     * remain the authoritative correctness guarantee.
     */
    public boolean acquireIdempotencyLock(UUID senderWalletId, String idempotencyKey, Duration ttl) {
        String keyHash = HashUtils.sha256Hex(senderWalletId + ":" + idempotencyKey);
        String redisKey = "payshield:idem:lock:" + keyHash;
        long nowMs = System.currentTimeMillis();

        if (!forceUnavailableForTest) {
            try {
                Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, String.valueOf(nowMs), ttl);
                lastRedisHealthy.set(true);
                return Boolean.TRUE.equals(acquired);
            } catch (Exception ex) {
                lastRedisHealthy.set(false);
            }
        }

        // Fallback lock cleanup and atomic putIfAbsent
        fallbackIdempotencyLocks.entrySet().removeIf(e -> e.getValue() < nowMs);
        Long existing = fallbackIdempotencyLocks.putIfAbsent(redisKey, nowMs + ttl.toMillis());
        return existing == null;
    }

    public void releaseIdempotencyLock(UUID senderWalletId, String idempotencyKey) {
        String keyHash = HashUtils.sha256Hex(senderWalletId + ":" + idempotencyKey);
        String redisKey = "payshield:idem:lock:" + keyHash;
        fallbackIdempotencyLocks.remove(redisKey);
        if (!forceUnavailableForTest) {
            try {
                redisTemplate.delete(redisKey);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Builds a context-aware SHA-256 signature for RAG dispute reports.
     * Any change in transaction status, risk score, analyst decision, or policy corpus version
     * automatically produces a distinct signature (context-aware invalidation).
     */
    public String computeRagCacheSignature(UUID transactionId,
                                           String status,
                                           double riskScore,
                                           String analystDecision,
                                           String policyCorpusVersion,
                                           String providerMode) {
        String rawContext = String.format("tx=%s|status=%s|score=%.4f|decision=%s|policyVer=%s|provider=%s",
                transactionId,
                status,
                riskScore,
                analystDecision != null ? analystDecision : "NONE",
                policyCorpusVersion,
                providerMode);
        return HashUtils.sha256Hex(rawContext);
    }

    public RagCacheLookup getCachedRagReport(String sha256Signature) {
        String redisKey = "payshield:rag:v1:" + sha256Signature;
        if (!forceUnavailableForTest) {
            try {
                String cached = redisTemplate.opsForValue().get(redisKey);
                lastRedisHealthy.set(true);
                if (cached != null && !cached.isBlank()) {
                    ragCacheHits.incrementAndGet();
                    ragCacheHitCounter.increment();
                    return new RagCacheLookup(true, sha256Signature, cached, "REDIS_6H_TTL");
                }
                ragCacheMisses.incrementAndGet();
                ragCacheMissCounter.increment();
                return new RagCacheLookup(false, sha256Signature, null, "REDIS_6H_TTL");
            } catch (Exception ex) {
                lastRedisHealthy.set(false);
            }
        }

        FallbackCacheEntry entry = fallbackRagCache.get(redisKey);
        if (entry != null && entry.expiresAt().isAfter(Instant.now())) {
            ragCacheHits.incrementAndGet();
            ragCacheHitCounter.increment();
            return new RagCacheLookup(true, sha256Signature, entry.jsonPayload(), "FALLBACK_MEMORY_6H_TTL");
        }
        if (entry != null) {
            fallbackRagCache.remove(redisKey);
        }
        ragCacheMisses.incrementAndGet();
        ragCacheMissCounter.increment();
        return new RagCacheLookup(false, sha256Signature, null, "FALLBACK_MEMORY_6H_TTL");
    }

    public void putCachedRagReport(UUID transactionId, String sha256Signature, String reportJson) {
        Duration ttl = Duration.ofHours(properties.getRisk().getRagCacheTtlHours());
        String redisKey = "payshield:rag:v1:" + sha256Signature;
        String txPointerKey = "payshield:rag:tx-latest:" + transactionId;

        fallbackRagCache.put(redisKey, new FallbackCacheEntry(reportJson, Instant.now().plus(ttl)));
        fallbackRagCache.put(txPointerKey, new FallbackCacheEntry(sha256Signature, Instant.now().plus(ttl)));

        if (!forceUnavailableForTest) {
            try {
                redisTemplate.opsForValue().set(redisKey, reportJson, ttl);
                redisTemplate.opsForValue().set(txPointerKey, sha256Signature, ttl);
                lastRedisHealthy.set(true);
            } catch (Exception ex) {
                lastRedisHealthy.set(false);
            }
        }
    }

    public void invalidateRagCacheForTransaction(UUID transactionId) {
        String txPointerKey = "payshield:rag:tx-latest:" + transactionId;
        FallbackCacheEntry pointer = fallbackRagCache.remove(txPointerKey);
        if (pointer != null) {
            fallbackRagCache.remove("payshield:rag:v1:" + pointer.jsonPayload());
        }
        if (!forceUnavailableForTest) {
            try {
                String sig = redisTemplate.opsForValue().get(txPointerKey);
                if (sig != null) {
                    redisTemplate.delete("payshield:rag:v1:" + sig);
                }
                redisTemplate.delete(txPointerKey);
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isRedisHealthy() {
        if (forceUnavailableForTest) {
            return false;
        }
        try {
            String pong = Optional.ofNullable(redisTemplate.getConnectionFactory())
                    .map(f -> f.getConnection().ping())
                    .orElse(null);
            boolean ok = pong != null && !pong.isBlank();
            lastRedisHealthy.set(ok);
            return ok;
        } catch (Exception ex) {
            lastRedisHealthy.set(false);
            return false;
        }
    }

    public long getRagCacheHits() {
        return ragCacheHits.get();
    }

    public long getRagCacheMisses() {
        return ragCacheMisses.get();
    }

    public void resetVelocityForUser(UUID userId) {
        String key = "payshield:velocity:" + userId;
        fallbackVelocityWindows.remove(key);
        if (!forceUnavailableForTest) {
            try {
                redisTemplate.delete(key);
            } catch (Exception ignored) {
            }
        }
    }

    public void setForceUnavailableForTest(boolean forceUnavailable) {
        this.forceUnavailableForTest = forceUnavailable;
    }

    public void clearAllForTest() {
        fallbackVelocityWindows.clear();
        fallbackRagCache.clear();
        fallbackIdempotencyLocks.clear();
        ragCacheHits.set(0);
        ragCacheMisses.set(0);
    }
}
