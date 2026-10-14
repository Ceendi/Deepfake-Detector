package com.deepfake.fileservice.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

// Mirrors the orchestrator test — the filter is a conscious duplicate, keep both in sync.
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void bindsProvidedHeaderToMdcForTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "cid-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenInChain = new String[1];
        FilterChain chain = (req, res) -> seenInChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY);

        filter.doFilter(request, response, chain);

        assertThat(seenInChain[0]).isEqualTo("cid-123");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull(); // cleared once the request ends
    }

    @Test
    void rejectsUnsafeAndOversizedIdsButAcceptsTheBoundary() throws Exception {
        for (String header : java.util.List.of("x".repeat(65), "bad_id", "bad id", "é", "bad\nline")) {
            var request = new MockHttpServletRequest(); request.addHeader(CorrelationIdFilter.HEADER, header);
            String[] seen = new String[1];
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen[0] = MDC.get(CorrelationIdFilter.MDC_KEY));
            assertThat(seen[0]).matches("[A-Za-z0-9-]{1,64}").isNotEqualTo(header);
        }
        var request = new MockHttpServletRequest(); request.addHeader(CorrelationIdFilter.HEADER, "x".repeat(64));
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("x".repeat(64)));
    }

    @Test
    void generatesCorrelationIdWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenInChain = new String[1];
        FilterChain chain = (req, res) -> seenInChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY);

        filter.doFilter(request, response, chain);

        assertThat(seenInChain[0]).isNotBlank();
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
