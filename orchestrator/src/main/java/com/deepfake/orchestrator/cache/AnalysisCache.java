package com.deepfake.orchestrator.cache;

import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
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
@RequiredArgsConstructor
public class AnalysisCache {

    private static final Duration TTL_BY_ID = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    // Own mapper so reads and writes here use identical serialization; the value never reaches the
    // client (the controller serializes the response separately), so the on-wire format is internal.
    private final JsonMapper json = JsonMapper.builder().build();

    private String keyById(UUID id) {
        return "cache:analysis:" + id;
    }

    public Optional<AnalysisResponse> getById(UUID id) {
        try {
            String raw = redis.opsForValue().get(keyById(id));
            return raw == null ? Optional.empty()
                    : Optional.of(json.readValue(raw, AnalysisResponse.class));
        } catch (DataAccessException | JacksonException e) {
            log.warn("cache getById degraded, falling back to DB: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void putById(AnalysisResponse a) {
        try {
            redis.opsForValue().set(keyById(a.id()), json.writeValueAsString(a), TTL_BY_ID);
        } catch (DataAccessException | JacksonException e) {
            log.warn("cache putById skipped (degraded): {}", e.getMessage());
        }
    }

    public void evictById(UUID id) {
        try {
            redis.delete(keyById(id));
        } catch (DataAccessException e) {
            log.warn("cache evictById skipped (degraded): {}", e.getMessage());
        }
    }
}
