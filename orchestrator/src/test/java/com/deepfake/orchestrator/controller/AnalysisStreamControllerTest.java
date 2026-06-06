package com.deepfake.orchestrator.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.deepfake.orchestrator.config.SecurityConfig;
import com.deepfake.orchestrator.config.WebConfig;
import com.deepfake.orchestrator.security.CurrentUserArgumentResolver;
import com.deepfake.orchestrator.security.JwtRoleConverter;
import com.deepfake.orchestrator.service.AnalysisService;

/**
 * SSE stream endpoint at the HTTP edge: owner opens a 200 text/event-stream; foreign/missing → 404
 * (IDOR, raised by the service); anonymous → 401.
 */
@WebMvcTest(AnalysisController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class, WebConfig.class, CurrentUserArgumentResolver.class})
class AnalysisStreamControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AnalysisService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private static SimpleGrantedAuthority userRole() {
        return new SimpleGrantedAuthority("ROLE_USER");
    }

    @Test
    void ownerOpensEventStream() throws Exception {
        UUID id = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter();
        when(service.openStream(eq(id), eq("user-a"))).thenReturn(emitter);

        MvcResult res = mvc.perform(get("/api/analysis/{id}/stream", id)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(request().asyncStarted())
                .andReturn();

        emitter.complete();
        mvc.perform(asyncDispatch(res))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    @Test
    void foreignOrMissingReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.openStream(eq(id), eq("user-b")))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mvc.perform(get("/api/analysis/{id}/stream", id)
                        .with(jwt().jwt(j -> j.subject("user-b")).authorities(userRole())))
                .andExpect(status().isNotFound());
    }

    @Test
    void withoutTokenReturns401() throws Exception {
        mvc.perform(get("/api/analysis/{id}/stream", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
