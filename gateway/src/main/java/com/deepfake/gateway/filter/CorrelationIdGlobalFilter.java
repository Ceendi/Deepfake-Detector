package com.deepfake.gateway.filter;

import java.util.UUID;
import java.util.regex.Pattern;

import io.micrometer.context.ContextRegistry;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Stamps every request with X-Correlation-ID (generated when the caller didn't send one) and echoes
 * it on the response. Reactor context restores MDC around callbacks even after a thread hop.
 */
@Component
class CorrelationIdGlobalFilter implements GlobalFilter, Ordered {

    CorrelationIdGlobalFilter() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(
                "correlationId", () -> MDC.get("correlationId"),
                value -> MDC.put("correlationId", value), () -> MDC.remove("correlationId"));
    }

    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    static final String HEADER = "X-Correlation-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String existing = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = (existing == null || !VALID_ID.matcher(existing).matches())
                ? UUID.randomUUID().toString() : existing;

        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> h.set(HEADER, correlationId)))
                .build();
        mutated.getResponse().getHeaders().set(HEADER, correlationId);
        return Mono.defer(() -> chain.filter(mutated)).contextWrite(context -> context.put("correlationId", correlationId));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
