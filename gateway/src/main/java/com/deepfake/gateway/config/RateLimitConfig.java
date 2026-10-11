package com.deepfake.gateway.config;

import java.time.Duration;
import java.util.List;
import com.deepfake.gateway.ratelimit.ProtectiveRedisRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import reactor.core.publisher.Mono;

@Configuration
class RateLimitConfig {

    @Bean
    ProtectiveRedisRateLimiter protectiveRedisRateLimiter(ReactiveStringRedisTemplate template,
            @Qualifier("redisRequestRateLimiterScript") RedisScript<List<Long>> script,
            ConfigurationService configurationService,
            @Value("${gateway.redis-limiter-budget:750ms}") Duration budget) {
        return new ProtectiveRedisRateLimiter(template, script, configurationService, budget);
    }

    /** Rate-limit bucket key = JWT subject, so limits are per user. */
    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .cast(JwtAuthenticationToken.class)
                .map(JwtAuthenticationToken::getName)
                .switchIfEmpty(Mono.just("anonymous")); // fallback; auth is required upstream
    }
}
