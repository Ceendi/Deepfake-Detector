package com.deepfake.orchestrator.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.web.server.ResponseStatusException;

import com.deepfake.orchestrator.config.SecurityConfig;
import com.deepfake.orchestrator.config.WebConfig;
import com.deepfake.orchestrator.report.ReportPdfService;
import com.deepfake.orchestrator.security.CurrentUserArgumentResolver;
import com.deepfake.orchestrator.security.JwtRoleConverter;
import com.deepfake.orchestrator.service.AnalysisService;
import com.deepfake.orchestrator.service.ArtifactService;

/**
 * HTTP contract of the artifact endpoint: auth (401 anonymous), the JWT subject reaching the
 * service as the owner, image/png + private cache headers on hit, and the service's 404 passing
 * through (IDOR-shaped, never 403).
 */
@WebMvcTest(AnalysisController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class, WebConfig.class, CurrentUserArgumentResolver.class,
        ReportPdfService.class})
class ArtifactControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AnalysisService service;

    @MockitoBean
    ArtifactService artifactService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    private final UUID id = UUID.randomUUID();

    @Test
    void anonymousGets401() throws Exception {
        mvc.perform(get("/api/analysis/{id}/artifacts/audio/gradcam.png", id))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerGetsPngWithPrivateCaching() throws Exception {
        when(artifactService.download(eq(id), eq("audio"), eq("gradcam.png"), eq("user-a")))
                .thenReturn(PNG);

        mvc.perform(get("/api/analysis/{id}/artifacts/audio/gradcam.png", id)
                        .with(jwt().jwt(j -> j.subject("user-a"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Cache-Control", "private, max-age=86400, immutable"))
                .andExpect(content().bytes(PNG));
    }

    @Test
    void serviceNotFoundPassesThroughAs404() throws Exception {
        when(artifactService.download(eq(id), eq("audio"), eq("gradcam.png"), eq("user-b")))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mvc.perform(get("/api/analysis/{id}/artifacts/audio/gradcam.png", id)
                        .with(jwt().jwt(j -> j.subject("user-b"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isNotFound());
    }
}
