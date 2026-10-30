package com.deepfake.orchestrator.sse;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.UUID;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.*;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

/** The writer must not start while the initial dispatch is still unwinding through servlet filters. */
class SseMvcLifecycleTest {
    final AnalysisStreamRegistry registry = new AnalysisStreamRegistry();
    final SseMvcLifecycle lifecycle = new SseMvcLifecycle(
            new StaticListableBeanFactory(java.util.Map.of("registry", registry)).getBeanProvider(AnalysisStreamRegistry.class));
    final AnalysisStreamRegistry.Session session = registry.session(registry.register(UUID.randomUUID(), "alice"));

    @AfterEach void release() { session.physicalCompletion(); }

    @Test void writerStartsOnlyAfterTheOutermostFilterUnwound() throws Exception {
        var request = asyncRequest();
        var insideChain = new boolean[1];
        Filter handler = (req, res, chain) -> {
            try { interceptor().afterConcurrentHandlingStarted(request, new MockHttpServletResponse(), new Object()); }
            catch (Exception failure) { throw new jakarta.servlet.ServletException(failure); }
            insideChain[0] = writerStarted();
        };
        lifecycle.sseDeliveryStart().getFilter().doFilter(request, new MockHttpServletResponse(), new MockFilterChain(new MockServlet(), handler));
        assertThat(insideChain[0]).isFalse();
        assertThat(writerStarted()).isTrue();
    }

    @Test void withoutTheOuterFilterTheWriterStartsAfterAsyncHandling() throws Exception {
        interceptor().afterConcurrentHandlingStarted(asyncRequest(), new MockHttpServletResponse(), new Object());
        assertThat(writerStarted()).isTrue();
    }

    private MockHttpServletRequest asyncRequest() {
        var request = new MockHttpServletRequest("GET", "/api/analysis/x/stream");
        request.setAsyncSupported(true);
        request.startAsync();
        request.setAttribute(SseMvcLifecycle.class.getName(), session);
        session.bind(); session.initialized();
        return request;
    }
    private boolean writerStarted() { synchronized (session) { return session.writerStarted; } }
    private AsyncHandlerInterceptor interceptor() {
        var interceptors = new InterceptorRegistry() { List<Object> all() { return getInterceptors(); } };
        lifecycle.addInterceptors(interceptors);
        return (AsyncHandlerInterceptor) interceptors.all().getFirst();
    }
    static final class MockServlet extends jakarta.servlet.http.HttpServlet { }
}
