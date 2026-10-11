package com.deepfake.orchestrator.sse;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.sse")
public record SseLimits(@DefaultValue("128") int globalConnections,
        @DefaultValue("4") int userConnections, @DefaultValue("4") int analysisConnections,
        @DefaultValue("10m") Duration lifetime, @DefaultValue("15s") Duration heartbeat,
        @DefaultValue("5s") Duration slowWrite) {
    public SseLimits {
        if (globalConnections < 1 || userConnections < 1 || analysisConnections < 1
                || lifetime.toMillis() < 1 || heartbeat.toMillis() < 1 || slowWrite.toMillis() < 1)
            throw new IllegalArgumentException("SSE limits and durations must be positive");
    }
    public static SseLimits defaults() {
        return new SseLimits(128, 4, 4, Duration.ofMinutes(10), Duration.ofSeconds(15), Duration.ofSeconds(5));
    }
}
