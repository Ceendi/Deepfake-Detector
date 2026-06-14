package com.deepfake.orchestrator.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import com.deepfake.orchestrator.config.SecurityConfig;
import com.deepfake.orchestrator.config.WebConfig;
import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.report.ReportPdfService;
import com.deepfake.orchestrator.security.CurrentUserArgumentResolver;
import com.deepfake.orchestrator.security.JwtRoleConverter;
import com.deepfake.orchestrator.service.AnalysisService;
import com.deepfake.orchestrator.service.ArtifactService;

/**
 * Web-layer wiring of the delete endpoint: {@code DELETE /api/analysis/{id}/record} -> 204, with the
 * service's 404 (IDOR) and 409 (in-progress) surfacing through the uniform error contract. Also pins
 * that the sibling {@code DELETE /api/analysis/{id}} still routes to cancel — the two must not collide.
 */
@WebMvcTest(AnalysisController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class, WebConfig.class, CurrentUserArgumentResolver.class,
        ReportPdfService.class})
class AnalysisDeleteControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AnalysisService service;

    @MockitoBean
    ArtifactService artifactService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private static SimpleGrantedAuthority userRole() {
        return new SimpleGrantedAuthority("ROLE_USER");
    }

    @Test
    void deleteReturns204() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(service).delete(eq(id), eq("user-a"));

        mvc.perform(delete("/api/analysis/{id}/record", id)
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isNoContent());

        verify(service).delete(id, "user-a");
    }

    @Test
    void idorReturns404WithErrorCode() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(service).delete(eq(id), eq("user-a"));

        mvc.perform(delete("/api/analysis/{id}/record", id)
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void inProgressReturns409WithConflictCode() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "cancel it first"))
                .when(service).delete(eq(id), eq("user-a"));

        mvc.perform(delete("/api/analysis/{id}/record", id)
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    // Regression guard: /{id}/record must not shadow the cancel route. DELETE /{id} (no suffix)
    // still hits cancel and returns the 200 Analysis body, not 204.
    @Test
    void cancelRouteIsUnchanged() throws Exception {
        UUID id = UUID.randomUUID();
        Analysis cancelled = Analysis.builder().id(id).userId("user-a")
                .type(AnalysisType.VIDEO).status(AnalysisStatus.CANCELLED).build();
        when(service.cancel(eq(id), eq("user-a"))).thenReturn(AnalysisResponse.from(cancelled));

        mvc.perform(delete("/api/analysis/{id}", id)
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
