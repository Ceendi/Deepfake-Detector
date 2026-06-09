package com.deepfake.orchestrator.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

class AnalysisMetricsTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @Test
    void terminalIncrementsCounterWithStatusAndTypeTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AnalysisMetrics metrics = new AnalysisMetrics(registry, redis);

        metrics.terminal(AnalysisStatus.COMPLETED, AnalysisType.FULL);

        assertThat(registry.get("analyses.total").tag("status", "completed").tag("type", "full")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void cacheHitAndMissAreSeparateSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AnalysisMetrics metrics = new AnalysisMetrics(registry, redis);

        metrics.cache(false);
        metrics.cache(true);

        assertThat(registry.get("cache.requests.total").tag("result", "miss").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("cache.requests.total").tag("result", "hit").counter().count()).isEqualTo(1.0);
    }

    @Test
    void durationRecordsTimerForType() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AnalysisMetrics metrics = new AnalysisMetrics(registry, redis);

        metrics.duration(AnalysisType.VIDEO, Duration.ofSeconds(5));

        assertThat(registry.get("analysis.duration").tag("type", "video").timer().count()).isEqualTo(1L);
    }

    // Pins the actual Prometheus exposition names — Micrometer must not double the _total suffix.
    @Test
    void prometheusScrapeUsesExpectedNames() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("analyses:inflight")).thenReturn("3");
        AnalysisMetrics metrics = new AnalysisMetrics(registry, redis);

        metrics.terminal(AnalysisStatus.FAILED, AnalysisType.AUDIO);
        metrics.cache(true);
        String scrape = registry.scrape();

        assertThat(scrape).contains("analyses_total{");
        assertThat(scrape).doesNotContain("analyses_total_total");
        assertThat(scrape).contains("cache_requests_total{");
        assertThat(scrape).doesNotContain("cache_requests_total_total");
        assertThat(scrape).contains("analyses_inflight");
    }
}
