package com.deepfake.orchestrator.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import com.deepfake.orchestrator.repository.AnalysisRepository;

import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

class AnalysisMetricsTest {

    private final AnalysisRepository repository = mock(AnalysisRepository.class);

    @Test
    void terminalIncrementsCounterWithStatusAndTypeTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AnalysisMetrics metrics = new AnalysisMetrics(registry, repository);

        metrics.terminal(AnalysisStatus.COMPLETED, AnalysisType.FULL);

        assertThat(registry.get("analyses.total").tag("status", "completed").tag("type", "full")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void cacheHitAndMissAreSeparateSeries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AnalysisMetrics metrics = new AnalysisMetrics(registry, repository);

        metrics.cache(false);
        metrics.cache(true);

        assertThat(registry.get("cache.requests.total").tag("result", "miss").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("cache.requests.total").tag("result", "hit").counter().count()).isEqualTo(1.0);
    }

    @Test
    void durationRecordsTimerForType() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AnalysisMetrics metrics = new AnalysisMetrics(registry, repository);

        metrics.duration(AnalysisType.VIDEO, Duration.ofSeconds(5));

        assertThat(registry.get("analysis.duration").tag("type", "video").timer().count()).isEqualTo(1L);
    }

    // D6 counters must exist at 0 right after construction (rate()/alerts need the series present
    // before the first failure) and move by exactly one per call.
    @Test
    void reliabilityCountersAreEagerlyRegisteredAndIncrement() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AnalysisMetrics metrics = new AnalysisMetrics(registry, repository);

        assertThat(registry.get("analyses.dlq").counter().count()).isZero();
        assertThat(registry.get("analyses.stuck.recovered").counter().count()).isZero();

        metrics.dlqFailure();
        metrics.stuckRecovery();
        metrics.stuckRecovery();

        assertThat(registry.get("analyses.dlq").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("analyses.stuck.recovered").counter().count()).isEqualTo(2.0);
    }

    // A fresh stack must expose every business series at 0, or dashboard panels read "No data".
    @Test
    void businessSeriesExistAtZeroBeforeAnyEvent() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new AnalysisMetrics(registry, repository);

        String scrape = registry.scrape();

        for (String status : new String[] {"completed", "failed", "cancelled"}) {
            for (String type : new String[] {"video", "audio", "full"}) {
                assertThat(scrape).contains(
                        "analyses_total{status=\"" + status + "\",type=\"" + type + "\"} 0.0");
            }
            assertThat(scrape).contains("analyses_deleted_total{status=\"" + status + "\"} 0.0");
        }
        assertThat(scrape).doesNotContain("status=\"pending\"").doesNotContain("status=\"processing\"");
        assertThat(scrape).contains("cache_requests_total{result=\"hit\"} 0.0");
        assertThat(scrape).contains("cache_requests_total{result=\"miss\"} 0.0");
    }

    // Pins the actual Prometheus exposition names — Micrometer must not double the _total suffix.
    @Test
    void prometheusScrapeUsesExpectedNames() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        when(repository.countActive()).thenReturn(3L);
        AnalysisMetrics metrics = new AnalysisMetrics(registry, repository);

        metrics.terminal(AnalysisStatus.FAILED, AnalysisType.AUDIO);
        metrics.cache(true);
        String scrape = registry.scrape();

        assertThat(scrape).contains("analyses_total{");
        assertThat(scrape).doesNotContain("analyses_total_total");
        assertThat(scrape).contains("cache_requests_total{");
        assertThat(scrape).doesNotContain("cache_requests_total_total");
        assertThat(scrape).contains("analyses_inflight 3.0");
        assertThat(scrape).contains("analyses_dlq_total");
        assertThat(scrape).doesNotContain("analyses_dlq_total_total");
        assertThat(scrape).contains("analyses_stuck_recovered_total");
    }
}
