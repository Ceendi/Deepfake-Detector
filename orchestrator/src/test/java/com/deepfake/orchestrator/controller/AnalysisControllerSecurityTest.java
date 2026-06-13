package com.deepfake.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import com.deepfake.orchestrator.config.SecurityConfig;
import com.deepfake.orchestrator.config.WebConfig;
import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import com.deepfake.orchestrator.dto.response.UserStatsResponse;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.report.ReportPdfService;
import com.deepfake.orchestrator.security.CurrentUserArgumentResolver;
import com.deepfake.orchestrator.security.JwtRoleConverter;
import com.deepfake.orchestrator.service.AnalysisService;
import com.deepfake.orchestrator.service.ArtifactService;

/**
 * End-to-edge auth behaviour of the analysis endpoints (no Keycloak, no DB): anonymous → 401,
 * authenticated without USER role → 403, owner → 200/201, IDOR → 404, invalid body → 400.
 * The JWT subject must reach the service as the owner id.
 */
@WebMvcTest(AnalysisController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class, WebConfig.class, CurrentUserArgumentResolver.class,
        ReportPdfService.class})
class AnalysisControllerSecurityTest {

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

    private static AnalysisResponse sample(UUID id, String userId) {
        return new AnalysisResponse(id, userId, "file-1", "key-1", AnalysisType.VIDEO,
                AnalysisStatus.PENDING, null, null, null, null, null, null,
                Instant.now(), Instant.now());
    }

    @Test
    void getWithoutTokenReturns401() throws Exception {
        mvc.perform(get("/api/analysis/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getWithoutUserRoleReturns403() throws Exception {
        mvc.perform(get("/api/analysis/{id}", UUID.randomUUID())
                        .with(jwt().jwt(j -> j.subject("user-a"))
                                .authorities(new SimpleGrantedAuthority("ROLE_GUEST"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getReturns200ForOwner() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(eq(id), eq("user-a"))).thenReturn(sample(id, "user-a"));

        mvc.perform(get("/api/analysis/{id}", id)
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isOk());
    }

    @Test
    void getReturns404ForDifferentUser() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(eq(id), eq("user-b")))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mvc.perform(get("/api/analysis/{id}", id)
                        .with(jwt().jwt(j -> j.subject("user-b")).authorities(userRole())))
                .andExpect(status().isNotFound());
    }

    @Test
    void createReturns201AndPassesJwtSubjectAsOwner() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any(), eq("user-a"))).thenReturn(sample(id, "user-a"));

        mvc.perform(post("/api/analysis")
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"file-1\",\"fileKey\":\"key-1\",\"type\":\"VIDEO\"}"))
                .andExpect(status().isCreated());

        verify(service).create(any(), eq("user-a"));
    }

    @Test
    void createWithBlankFileIdReturns400() throws Exception {
        mvc.perform(post("/api/analysis")
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"\",\"fileKey\":\"key-1\",\"type\":\"VIDEO\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statsWithoutTokenReturns401() throws Exception {
        mvc.perform(get("/api/analysis/stats"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void statsReturns200AndIsScopedToJwtSubject() throws Exception {
        // also proves "/stats" resolves to the stats handler, not the "/{id}" template
        when(service.stats("user-a")).thenReturn(new UserStatsResponse(
                3,
                new UserStatsResponse.StatusCounts(2, 1, 0, 0),
                new UserStatsResponse.TypeCounts(2, 1, 0),
                new UserStatsResponse.VerdictCounts(1, 1),
                0.74, 3, Instant.now()));

        mvc.perform(get("/api/analysis/stats")
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.byStatus.completed").value(2))
                .andExpect(jsonPath("$.verdicts.fake").value(1));

        verify(service).stats("user-a");
    }

    @Test
    void openApiDocsPathIsPublic() throws Exception {
        // Security must not reject the docs path with 401 (no springdoc handler in the slice,
        // so the exact downstream status is irrelevant — only "not blocked by auth" matters).
        mvc.perform(get("/v3/api-docs"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(401));
    }
}
