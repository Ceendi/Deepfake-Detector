package com.deepfake.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.filter.factory.RequestSizeGatewayFilterFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayUploadSizeTest {
    @Test void requestSizeAllows500MiBFilePlusMultipartOverhead() throws Exception {
        var properties = new YamlPropertySourceLoader().load("gateway", new ClassPathResource("application.yaml")).getFirst();
        var configured = (String) properties.getProperty("spring.cloud.gateway.server.webflux.default-filters[0].args.maxSize");
        var factory = new RequestSizeGatewayFilterFactory();
        var config = new RequestSizeGatewayFilterFactory.RequestSizeConfig();
        config.setMaxSize(DataSize.parse(configured));
        var filter = factory.apply(config);
        for (long size : new long[] {500L * 1024 * 1024 + 1024, 501L * 1024 * 1024, 501L * 1024 * 1024 + 1}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/files/upload")
                    .header("Content-Length", Long.toString(size)));
            filter.filter(exchange, forwarded -> {
                forwarded.getResponse().setStatusCode(HttpStatus.OK);
                return forwarded.getResponse().setComplete();
            }).block();
            assertThat(exchange.getResponse().getStatusCode().value()).as("request bytes %s", size)
                    .isEqualTo(size <= 501L * 1024 * 1024 ? 200 : 413);
        }
    }
}
