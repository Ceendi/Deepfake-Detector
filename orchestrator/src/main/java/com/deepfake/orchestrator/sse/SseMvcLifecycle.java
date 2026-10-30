package com.deepfake.orchestrator.sse;

import java.io.IOException;
import java.util.ArrayList;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations;
import org.springframework.context.annotation.*;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.support.*;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.*;

/**
 * Records successful emitter initialization, then starts delivery once the initial dispatch has left
 * every servlet filter: filters such as Spring Security's HeaderWriterFilter still mutate response headers
 * while unwinding, and a writer committing concurrently corrupts Tomcat's header list.
 */
@Configuration
@EnableConfigurationProperties(SseLimits.class)
public class SseMvcLifecycle implements WebMvcConfigurer {
    private static final String SESSION = SseMvcLifecycle.class.getName();
    private static final String DEFERRED = SESSION + ".deferred";
    private static final String READY = SESSION + ".ready";
    private final ObjectProvider<AnalysisStreamRegistry> registries;
    public SseMvcLifecycle(ObjectProvider<AnalysisStreamRegistry> registries) { this.registries=registries; }
    @Bean public FilterRegistrationBean<DeliveryStartFilter> sseDeliveryStart() {
        var registration = new FilterRegistrationBean<>(new DeliveryStartFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE); // Outermost: no filter may touch the response afterwards.
        return registration;
    }
    static final class DeliveryStartFilter extends OncePerRequestFilter {
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            request.setAttribute(DEFERRED, Boolean.TRUE);
            try { chain.doFilter(request, response); }
            finally {
                if (request.getAttribute(READY) instanceof AnalysisStreamRegistry.Session session) {
                    request.removeAttribute(READY);
                    session.ready();
                }
            }
        }
    }
    @Bean public WebMvcRegistrations sseMvcRegistrations() {
        return new WebMvcRegistrations() {
            @Override public RequestMappingHandlerAdapter getRequestMappingHandlerAdapter() {
                return new RequestMappingHandlerAdapter() {
                    @Override public void afterPropertiesSet() {
                        super.afterPropertiesSet();
                        var handlers = new ArrayList<HandlerMethodReturnValueHandler>();
                        for (var handler : getReturnValueHandlers()) {
                            handlers.add(handler instanceof ResponseBodyEmitterReturnValueHandler ? tracked(handler) : handler);
                        }
                        setReturnValueHandlers(handlers);
                    }
                };
            }
        };
    }
    private HandlerMethodReturnValueHandler tracked(HandlerMethodReturnValueHandler delegate) {
        return new HandlerMethodReturnValueHandler() {
            @Override public boolean supportsReturnType(MethodParameter type) { return delegate.supportsReturnType(type); }
            @Override public void handleReturnValue(Object value, MethodParameter type,
                    ModelAndViewContainer container, NativeWebRequest web) throws Exception {
                var registry=registries.getIfAvailable();
                var session = registry != null && value instanceof SseEmitter emitter ? registry.session(emitter) : null;
                if (session == null) { delegate.handleReturnValue(value,type,container,web); return; }
                HttpServletRequest request=web.getNativeRequest(HttpServletRequest.class);
                request.setAttribute(SESSION,session); session.bind();
                try {
                    delegate.handleReturnValue(value,type,container,web);
                    session.initialized();
                } catch (Exception | Error failure) {
                    session.initializationFailed();
                    throw failure;
                }
            }
        };
    }
    @Override public void addInterceptors(InterceptorRegistry interceptors) {
        interceptors.addInterceptor(new AsyncHandlerInterceptor() {
            @Override public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
                var session=(AnalysisStreamRegistry.Session)request.getAttribute(SESSION);
                if (session == null) return;
                try {
                    request.getAsyncContext().addListener(new AsyncListener() {
                        public void onComplete(AsyncEvent event) { session.physicalCompletion(); }
                        public void onError(AsyncEvent event) { session.close(); }
                        public void onTimeout(AsyncEvent event) { session.close(); }
                        public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                    });
                } catch (IllegalStateException alreadyCompleted) { session.physicalCompletion(); }
                if (WebAsyncUtils.getAsyncManager(request).getConcurrentResult() instanceof Throwable) session.initializationFailed();
                // Success is recorded separately: async-started alone does not prove initialization.
                if (request.getAttribute(DEFERRED) != null) request.setAttribute(READY, session);
                else session.ready(); // Without the outer filter (e.g. standalone MockMvc) keep the old start point.
            }
            @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
                var session=(AnalysisStreamRegistry.Session)request.getAttribute(SESSION);
                if (session != null && request.getDispatcherType() == DispatcherType.REQUEST && !request.isAsyncStarted()) session.physicalCompletion();
            }
        });
    }
}
