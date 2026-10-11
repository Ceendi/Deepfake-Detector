package com.deepfake.orchestrator.sse;

import java.util.ArrayList;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations;
import org.springframework.context.annotation.*;
import org.springframework.core.MethodParameter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.*;

/** Records successful emitter initialization, then starts delivery after MVC return-value handling. */
@Configuration
@EnableConfigurationProperties(SseLimits.class)
public class SseMvcLifecycle implements WebMvcConfigurer {
    private static final String SESSION = SseMvcLifecycle.class.getName();
    private final ObjectProvider<AnalysisStreamRegistry> registries;
    public SseMvcLifecycle(ObjectProvider<AnalysisStreamRegistry> registries) { this.registries=registries; }
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
                session.ready(); // Success is recorded separately: async-started alone does not prove initialization.
            }
            @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
                var session=(AnalysisStreamRegistry.Session)request.getAttribute(SESSION);
                if (session != null && request.getDispatcherType() == DispatcherType.REQUEST && !request.isAsyncStarted()) session.physicalCompletion();
            }
        });
    }
}
