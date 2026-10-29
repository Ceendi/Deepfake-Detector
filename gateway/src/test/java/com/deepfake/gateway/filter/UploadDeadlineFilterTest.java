package com.deepfake.gateway.filter;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;
import static org.assertj.core.api.Assertions.*;

class UploadDeadlineFilterTest {
    @Test void totalDeadlineCancelsSlowUploadAndReturns408() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/files/upload"));
        var upstream = PublisherProbe.of(Mono.<Void>never());
        StepVerifier.withVirtualTime(() -> new UploadDeadlineFilter(Duration.ofMinutes(15))
                .filter(exchange, ignored -> upstream.mono()))
                .thenAwait(Duration.ofMinutes(15)).verifyComplete();
        upstream.assertWasCancelled();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.REQUEST_TIMEOUT);
    }

    @Test void otherRoutesHaveNoUploadDeadline() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/analysis/id/stream"));
        StepVerifier.withVirtualTime(() -> new UploadDeadlineFilter(Duration.ofSeconds(1))
                .filter(exchange, ignored -> Mono.delay(Duration.ofHours(1)).then()))
                .thenAwait(Duration.ofHours(1)).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test void matrixParametersDoNotBypassDeadline() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/files/upload;part=1"));
        StepVerifier.withVirtualTime(() -> new UploadDeadlineFilter(Duration.ofSeconds(1))
                .filter(exchange, ignored -> Mono.never())).thenAwait(Duration.ofSeconds(1)).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.REQUEST_TIMEOUT);
    }

    @Test void quickUploadsAndDependencyFailuresKeepTheirResponse() {
        var filter = new UploadDeadlineFilter(Duration.ofSeconds(1));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/files/upload"));
        filter.filter(exchange, ignored -> Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        var failure = new IllegalStateException("upstream");
        StepVerifier.create(filter.filter(exchange, ignored -> Mono.error(failure))).expectErrorMatches(e -> e == failure).verify();
        assertThatThrownBy(() -> new UploadDeadlineFilter(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
