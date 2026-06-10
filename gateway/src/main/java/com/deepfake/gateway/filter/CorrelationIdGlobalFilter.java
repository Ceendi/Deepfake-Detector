package com.deepfake.gateway.filter;

import java.util.UUID;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Stamps every request with X-Correlation-ID (generated when the caller didn't send one) and echoes
 * it on the response, so one id spans gateway -> services -> detectors. No MDC here: WebFlux has no
 * stable thread-local, so the servlet services bind the header to their own MDC.
 */
@Component
class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    static final String HEADER = "X-Correlation-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String existing = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = (existing == null || existing.isBlank())
                ? UUID.randomUUID().toString() : existing;

        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> h.set(HEADER, correlationId)))
                .build();
        mutated.getResponse().getHeaders().set(HEADER, correlationId);
        return chain.filter(mutated);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
