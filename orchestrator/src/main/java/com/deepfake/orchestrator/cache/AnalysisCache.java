package com.deepfake.orchestrator.cache;

import com.deepfake.orchestrator.redis.OptionalRedisOperations;
import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Cache-aside for a single analysis by id. Caches the full AnalysisResponse (including userId) so
 * the IDOR guard can run on the caller side after the read — never cache an already-authorized
 * per-user view. Every Redis op is wrapped: on failure we log and degrade to the DB (D6).
 */
@Slf4j
@Component
@Import(OptionalRedisOperations.class)
public class AnalysisCache {

    private static final Duration TTL_BY_ID = Duration.ofSeconds(60);

    // Bounded stripes fence fills that began before an eviction in this single-instance service.
    private final Object[] fillLocks = java.util.stream.IntStream.range(0, 64)
            .mapToObj(i -> new Object()).toArray();
    private final long[] generations = new long[64];

    public record FillToken(int stripe, long generation, String namespace) {}

    public FillToken beginFill(UUID id) {
        int stripe = Math.floorMod(id.hashCode(), fillLocks.length);
        synchronized (fillLocks[stripe]) {
            return new FillToken(stripe, generations[stripe], optionalRedis.cacheNamespace());
        }
    }

    private final StringRedisTemplate redis;
    private final OptionalRedisOperations optionalRedis;
    // Toggle (default on) so the MO benchmark can measure the cache-hit vs forced-miss path without
    // flushing Redis; off => getById always misses and putById is a no-op.
    private final boolean enabled;
    // Own mapper so reads and writes here use identical serialization; the value never reaches the
    // client (the controller serializes the response separately), so the on-wire format is internal.
    private final JsonMapper json = JsonMapper.builder().build();

    public AnalysisCache(StringRedisTemplate redis, OptionalRedisOperations optionalRedis,
                         @Value("${cache.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.optionalRedis = optionalRedis;
        this.enabled = enabled;
    }

    private String keyById(UUID id) {
        return "cache:analysis:" + id + optionalRedis.cacheNamespace();
    }

    public Optional<AnalysisResponse> getById(UUID id) {
        if (!enabled) return Optional.empty();
        try {
            String raw = optionalRedis.get("cache read", () -> redis.opsForValue().get(keyById(id)), null);
            return raw == null ? Optional.empty()
                    : Optional.of(json.readValue(raw, AnalysisResponse.class));
        } catch (JacksonException e) {
            log.warn("cache getById degraded, falling back to DB: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void putById(AnalysisResponse a) {
        putById(a, beginFill(a.id()));
    }

    public void putById(AnalysisResponse a, FillToken token) {
        if (!enabled) return;
        try {
            String raw = json.writeValueAsString(a);
            synchronized (fillLocks[token.stripe()]) {
                if (generations[token.stripe()] != token.generation()
                        || !optionalRedis.cacheNamespace().equals(token.namespace())) return;
                String key = "cache:analysis:" + a.id() + token.namespace();
                optionalRedis.run("cache write", () -> redis.opsForValue().set(key, raw, TTL_BY_ID));
            }
        } catch (JacksonException e) {
            log.warn("cache putById skipped (degraded): {}", e.getMessage());
        }
    }

    public void evictById(UUID id) {
        int stripe = Math.floorMod(id.hashCode(), fillLocks.length);
        synchronized (fillLocks[stripe]) {
            generations[stripe]++;
            optionalRedis.run("cache eviction", () -> redis.delete(keyById(id)));
        }
    }
}
