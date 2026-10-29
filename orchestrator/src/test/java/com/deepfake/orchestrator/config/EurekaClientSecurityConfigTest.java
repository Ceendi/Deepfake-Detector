package com.deepfake.orchestrator.config;

import com.netflix.discovery.shared.resolver.DefaultEndpoint;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.configuration.TlsProperties;
import org.springframework.cloud.netflix.eureka.EurekaClientConfigBean;
import org.springframework.cloud.netflix.eureka.http.RestClientTransportClientFactories;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EurekaClientSecurityConfigTest {
    @Test
    void discoveryTransportSendsCsrfHeaderAndBasicCredentials() throws Exception {
        var csrf = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/eureka/apps/", exchange -> {
            csrf.set(exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"applications\":{\"application\":[]}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var args = new EurekaClientSecurityConfig().registryClientArgs(new TlsProperties(),
                    (context, verifier) -> new SimpleClientHttpRequestFactory(), "disposable-csrf-token");
            var factory = new RestClientTransportClientFactories(args).newTransportClientFactory(
                    new EurekaClientConfigBean(), List.of(), null);
            try {
                var client = factory.newClient(new DefaultEndpoint(
                        "http://test-user:test-pass@127.0.0.1:" + server.getAddress().getPort() + "/eureka/"));
                try {
                    assertEquals(200, client.getApplications().getStatusCode());
                    assertEquals("disposable-csrf-token", csrf.get());
                    assertEquals("Basic dGVzdC11c2VyOnRlc3QtcGFzcw==", authorization.get());
                } finally {
                    client.shutdown();
                }
            } finally {
                factory.shutdown();
            }
        } finally {
            server.stop(0);
        }
    }
}
