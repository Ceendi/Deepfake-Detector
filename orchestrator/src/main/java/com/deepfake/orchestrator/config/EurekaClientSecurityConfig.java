package com.deepfake.orchestrator.config;

import java.io.IOException;
import java.security.GeneralSecurityException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.configuration.TlsProperties;
import org.springframework.cloud.netflix.eureka.config.DiscoveryClientOptionalArgsConfiguration;
import org.springframework.cloud.netflix.eureka.http.EurekaClientHttpRequestFactorySupplier;
import org.springframework.cloud.netflix.eureka.http.RestClientDiscoveryClientOptionalArgs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"eureka.client.enabled", "spring.cloud.discovery.enabled"}, matchIfMissing = true)
class EurekaClientSecurityConfig {
    @Bean
    RestClientDiscoveryClientOptionalArgs registryClientArgs(
            TlsProperties tls, EurekaClientHttpRequestFactorySupplier factory,
            @Value("${EUREKA_CSRF_TOKEN}") String csrfToken) throws GeneralSecurityException, IOException {
        var args = new RestClientDiscoveryClientOptionalArgs(factory,
                () -> RestClient.builder().defaultHeader("X-CSRF-TOKEN", csrfToken));
        DiscoveryClientOptionalArgsConfiguration.setupTLS(args, tls);
        return args;
    }
}
