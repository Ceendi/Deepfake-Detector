package com.deepfake.gateway.config;

import java.security.Principal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RateLimitConfigTest {

    private final KeyResolver resolver = new RateLimitConfig().userKeyResolver();

    @Test
    void resolvesKeyFromJwtSubject() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user-a")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        ServerWebExchange exchange = exchangeWithPrincipal(new JwtAuthenticationToken(jwt));

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("user-a")
                .verifyComplete();
    }

    @Test
    void fallsBackToAnonymousWhenNoPrincipal() {
        ServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/analysis"));

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("anonymous")
                .verifyComplete();
    }

    private static ServerWebExchange exchangeWithPrincipal(Principal principal) {
        ServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/analysis"));
        return new ServerWebExchangeDecorator(exchange) {
            @Override
            @SuppressWarnings("unchecked")
            public <T extends Principal> Mono<T> getPrincipal() {
                return Mono.just((T) principal);
            }
        };
    }
}
