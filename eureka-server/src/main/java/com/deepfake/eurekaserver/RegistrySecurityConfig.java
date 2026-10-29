package com.deepfake.eurekaserver;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class RegistrySecurityConfig {
    @Bean
    SecurityFilterChain registrySecurity(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                // Eureka clients use Basic credentials and cannot obtain browser CSRF tokens.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/eureka/**"))
                .build();
    }
}
