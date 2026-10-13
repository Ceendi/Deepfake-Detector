package com.deepfake.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

class CorrelationIdGlobalFilterTest {

    private final CorrelationIdGlobalFilter filter = new CorrelationIdGlobalFilter();

    @Test
    void propagatesCallerHeaderDownstreamAndEchoesOnResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/analysis")
                        .header(CorrelationIdGlobalFilter.HEADER, "cid-123"));
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        GatewayFilterChain chain = e -> {
            downstream.set(e);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstream.get().getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER))
                .isEqualTo("cid-123");
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER))
                .isEqualTo("cid-123");
    }

    @Test
    void generatesIdWhenHeaderMissingAndUsesItOnBothSides() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/analysis"));
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        GatewayFilterChain chain = e -> {
            downstream.set(e);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        String requestId = downstream.get().getRequest().getHeaders()
                .getFirst(CorrelationIdGlobalFilter.HEADER);
        assertThat(requestId).isNotBlank();
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER))
                .isEqualTo(requestId);
    }

    @Test
    void rejectsUnsafeAndOversizedIdsAndEchoesTheReplacement() {
        for (String header : java.util.List.of("x".repeat(65), "bad_id", "bad id", "é")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/analysis")
                    .header(CorrelationIdGlobalFilter.HEADER, header));
            filter.filter(exchange, downstream -> {
                String id = downstream.getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER);
                assertThat(id).matches("[A-Za-z0-9-]{1,64}").isNotEqualTo(header);
                assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER)).isEqualTo(id);
                return Mono.empty();
            }).block();
        }
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/analysis")
                .header(CorrelationIdGlobalFilter.HEADER, "x".repeat(64)));
        filter.filter(exchange, downstream -> {
            assertThat(downstream.getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER)).isEqualTo("x".repeat(64));
            return Mono.empty();
        }).block();
    }

    @Test
    void generatesIdWhenHeaderBlank() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/analysis")
                        .header(CorrelationIdGlobalFilter.HEADER, "  "));
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        GatewayFilterChain chain = e -> {
            downstream.set(e);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstream.get().getRequest().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER))
                .isNotBlank()
                .isNotEqualTo("  ");
    }

    // Must stamp before any other filter (routing, rate-limit logs) sees the request.
    @Test
    void runsAtHighestPrecedence() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
