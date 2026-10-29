package com.deepfake.gateway.filter;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

@Component
class UploadDeadlineFilter implements GlobalFilter, Ordered {
    private static final PathPattern UPLOAD = PathPatternParser.defaultInstance.parse("/api/files/upload");
    private final Duration deadline;

    UploadDeadlineFilter(@Value("${gateway.upload-deadline:15m}") Duration deadline) {
        if (deadline.isNegative() || deadline.isZero()) {
            throw new IllegalArgumentException("Upload deadline must be positive");
        }
        this.deadline = deadline;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (exchange.getRequest().getMethod() != HttpMethod.POST
                || !UPLOAD.matches(exchange.getRequest().getPath().pathWithinApplication())) {
            return chain.filter(exchange);
        }
        // Cancellation disposes the downstream HTTP connection, releasing the replica's body reader.
        return chain.filter(exchange).timeout(deadline, Mono.defer(() -> {
            exchange.getResponse().setStatusCode(HttpStatus.REQUEST_TIMEOUT);
            return exchange.getResponse().setComplete();
        }));
    }

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 1; }
}
