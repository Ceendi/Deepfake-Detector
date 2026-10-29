package com.deepfake.orchestrator.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class FileReleaseClientTest {
    @Test void releaseUsesCallerJwtAndTreats404AsAlreadyReleased() throws Exception {
        var status = new AtomicInteger(204);
        var file = UUID.randomUUID();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/files/" + file, exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("DELETE");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer jwt");
            assertThat(exchange.getRequestHeaders().getFirst("X-Correlation-ID")).isEqualTo("release-test");
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        try {
            MDC.put("correlationId", "release-test");
            var client = new FileMetadataClient("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5));
            client.release(file.toString(), "jwt");
            status.set(404);
            client.release(file.toString(), "jwt");
            for (int code : new int[]{401, 403, 500, 503}) {
                status.set(code);
                assertThatThrownBy(() -> client.release(file.toString(), "jwt"))
                        .isInstanceOf(ResponseStatusException.class)
                        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.SERVICE_UNAVAILABLE);
            }
        } finally { server.stop(0); MDC.remove("correlationId"); }
    }
}
