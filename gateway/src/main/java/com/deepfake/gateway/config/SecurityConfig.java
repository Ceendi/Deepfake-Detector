package com.deepfake.gateway.config;

import static org.springframework.security.config.Customizer.withDefaults;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

import com.deepfake.gateway.security.JwtRoleConverter;

import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${gateway.cors-origins}") String origins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    @Bean
    SecurityWebFilterChain chain(ServerHttpSecurity http, JwtRoleConverter jwtConverter,
                                Environment environment) {
        return http
                .cors(withDefaults())
                .csrf(ServerHttpSecurity.CsrfSpec::disable) // stateless API
                .authorizeExchange(ex -> ex
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                        .access((authentication, context) -> {
                            var address = context.getExchange().getRequest().getLocalAddress();
                            // Use the bound socket, never a forwarded header, to identify internal traffic.
                            int managementPort = environment.getProperty("local.management.port", Integer.class, -1);
                            return Mono.just(new AuthorizationDecision(address != null
                                    && address.getPort() == managementPort));
                        })
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
                .build();
    }
}
