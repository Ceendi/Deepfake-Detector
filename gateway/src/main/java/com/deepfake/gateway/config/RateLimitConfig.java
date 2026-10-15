package com.deepfake.gateway.config;

import java.time.Duration;
import java.util.List;

import com.deepfake.gateway.ratelimit.ProtectiveRedisRateLimiter;
import com.deepfake.gateway.ratelimit.RateLimiterWarmup;
import com.deepfake.gateway.ratelimit.RedisLimiterDegradation;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import reactor.core.publisher.Mono;

@Configuration
class RateLimitConfig {

    @Bean
    RedisLimiterDegradation redisLimiterDegradation(
            @Value("${gateway.redis-degraded-bypass:2s}") Duration bypass) {
        return new RedisLimiterDegradation(bypass);
    }

    @Bean
    @Primary
    ProtectiveRedisRateLimiter protectiveRedisRateLimiter(ReactiveStringRedisTemplate template,
            @Qualifier("redisRequestRateLimiterScript") RedisScript<List<Long>> script,
            ConfigurationService configurationService,
            @Value("${gateway.redis-limiter-budget:750ms}") Duration budget,
            RedisLimiterDegradation degradation, MeterRegistry meters) {
        return new ProtectiveRedisRateLimiter(template, script, configurationService, budget,
                degradation, ProtectiveRedisRateLimiter.FailureMode.CLOSED, meters);
    }

    @Bean
    ProtectiveRedisRateLimiter availableRedisRateLimiter(ReactiveStringRedisTemplate template,
            @Qualifier("redisRequestRateLimiterScript") RedisScript<List<Long>> script,
            ConfigurationService configurationService,
            @Value("${gateway.redis-limiter-budget:750ms}") Duration budget,
            RedisLimiterDegradation degradation, MeterRegistry meters) {
        return new ProtectiveRedisRateLimiter(template, script, configurationService, budget,
                degradation, ProtectiveRedisRateLimiter.FailureMode.OPEN, meters);
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> rateLimiterWarmup(ReactiveStringRedisTemplate template,
            @Qualifier("redisRequestRateLimiterScript") RedisScript<List<Long>> script) {
        return event -> RateLimiterWarmup.run(template, script, Duration.ofSeconds(5)).block();
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
