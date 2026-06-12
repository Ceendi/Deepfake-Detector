package com.deepfake.orchestrator.metrics;

import java.time.Duration;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Business metrics (D3). Names are dotted here; Prometheus renders them with underscores and a
 * {@code _total} suffix on counters (see docs/contracts/metrics.md). Tags are enum-valued only —
 * never analysis_id/user_id — to bound series cardinality.
 */
@Component
public class AnalysisMetrics {

    private static final String INFLIGHT_KEY = "analyses:inflight";

    private final MeterRegistry registry;
    private final StringRedisTemplate redis;
    private final Counter dlqFailures;
    private final Counter stuckRecoveries;

    public AnalysisMetrics(MeterRegistry registry, StringRedisTemplate redis) {
        this.registry = registry;
        this.redis = redis;
        // Registered once; sampled on each scrape, fail-open to 0 so a Redis outage never 500s a scrape.
        Gauge.builder("analyses.inflight", this, AnalysisMetrics::readInflight).register(registry);
        // D6 reliability counters, registered eagerly so the series exist at 0 from the first
        // scrape — rate()/alerts need them present before the first failure ever happens.
        this.dlqFailures = registry.counter("analyses.dlq");
        this.stuckRecoveries = registry.counter("analyses.stuck.recovered");
    }

    /** One terminal transition (COMPLETED/FAILED/CANCELLED) of a given type. */
    public void terminal(AnalysisStatus status, AnalysisType type) {
        registry.counter("analyses.total", "status", status.name().toLowerCase(),
                "type", type.name().toLowerCase()).increment();
    }

    /** End-to-end latency (created -> completed); recorded for COMPLETED only — see the caller. */
    public void duration(AnalysisType type, Duration elapsed) {
        registry.timer("analysis.duration", "type", type.name().toLowerCase()).record(elapsed);
    }

    /** One analysis failed via a dead-lettered message (D6); cause-specific on top of analyses_total. */
    public void dlqFailure() {
        dlqFailures.increment();
    }

    /** One analysis auto-failed by stuck-job recovery (D6); cause-specific on top of analyses_total. */
    public void stuckRecovery() {
        stuckRecoveries.increment();
    }

    public void cache(boolean hit) {
        registry.counter("cache.requests.total", "result", hit ? "hit" : "miss").increment();
    }

    private double readInflight() {
        try {
            String v = redis.opsForValue().get(INFLIGHT_KEY);
            return v == null ? 0 : Double.parseDouble(v);
        } catch (DataAccessException | NumberFormatException e) {
            return 0; // gauge fail-open
        }
    }
}
