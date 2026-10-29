package com.deepfake.gateway.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false", "spring.data.redis.password=test", "management.server.port=0"})
@Import(GatewayLoggingIntegrationTest.ProbeConfig.class)
class GatewayLoggingIntegrationTest {
    @LocalServerPort int port;
    @MockitoBean ReactiveJwtDecoder decoder;

    @Test void asynchronousGatewayLogContainsTraceAndCorrelation() throws Exception {
        when(decoder.decode("test-token")).thenReturn(Mono.just(Jwt.withTokenValue("test-token")
                .header("alg", "none").subject("alice").claim("realm_access", Map.of("roles", java.util.List.of("USER"))).build()));
        var captured = new AtomicReference<Map<String, String>>();
        var logger = (Logger) LoggerFactory.getLogger(Probe.class);
        var appender = new AppenderBase<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) { captured.set(Map.copyOf(event.getMDCPropertyMap())); }
        };
        appender.start(); logger.addAppender(appender);
        String trace = "1234567890abcdef1234567890abcdef";
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/logging-test"))
                    .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer test-token")
                    .header("X-Correlation-ID", "gateway-log-test")
                    .header("traceparent", "00-" + trace + "-1234567890abcdef-01").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(captured.get()).containsEntry("correlationId", "gateway-log-test");
            assertThat(captured.get().get("traceId")).matches("[0-9a-f]{32}");
            assertThat(captured.get().get("spanId")).isNotBlank();
        } finally { logger.detachAppender(appender); appender.stop(); }
        assertThat(org.slf4j.MDC.get("correlationId")).isNull();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(Probe.class)
    static class ProbeConfig {
        @Bean RouteLocator loggingRoute(RouteLocatorBuilder builder) {
            return builder.routes().route("logging-test", p -> p.path("/api/logging-test").uri("forward:/logging-probe")).build();
        }
    }
    @RestController
    @org.springframework.boot.test.context.TestComponent
    static class Probe {
        @GetMapping("/logging-probe") Mono<String> log() {
            return Mono.delay(Duration.ofMillis(1)).map(value -> {
                LoggerFactory.getLogger(Probe.class).warn("Asynchronous gateway probe");
                return "ok";
            });
        }
    }
}
