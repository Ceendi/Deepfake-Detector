package com.deepfake.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
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
import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.report.ReportPdfService;
import com.deepfake.orchestrator.security.CurrentUserArgumentResolver;
import com.deepfake.orchestrator.security.JwtRoleConverter;
import com.deepfake.orchestrator.service.AnalysisService;

/**
 * report.pdf endpoint: owner of a COMPLETED analysis gets a real PDF (200, attachment); a non-COMPLETED
 * analysis is 409; a foreign/missing one is 404 (IDOR, via the reused service.get guard); anonymous 401.
 */
@WebMvcTest(AnalysisController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class, WebConfig.class,
        CurrentUserArgumentResolver.class, ReportPdfService.class})
class AnalysisReportControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AnalysisService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private static SimpleGrantedAuthority userRole() {
        return new SimpleGrantedAuthority("ROLE_USER");
    }

    private static AnalysisResponse withStatus(UUID id, String userId, AnalysisStatus status) {
        String verdict = status == AnalysisStatus.COMPLETED ? "FAKE" : null;
        BigDecimal confidence = status == AnalysisStatus.COMPLETED ? new BigDecimal("0.74") : null;
        return new AnalysisResponse(id, userId, "file-1", "key-1", AnalysisType.VIDEO, status,
                verdict, confidence, new BigDecimal("0.87"), null, null, null, Instant.now(), Instant.now());
    }

    @Test
    void reportWithoutTokenReturns401() throws Exception {
        mvc.perform(get("/api/analysis/{id}/report.pdf", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reportForOwnerCompletedReturnsPdf() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(eq(id), eq("user-a"))).thenReturn(withStatus(id, "user-a", AnalysisStatus.COMPLETED));

        mvc.perform(get("/api/analysis/{id}/report.pdf", id)
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"report-" + id + ".pdf\""))
                .andExpect(r -> assertThat(r.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PDF_VALUE))
                .andExpect(r -> assertThat(r.getResponse().getContentAsByteArray()).startsWith("%PDF-".getBytes()));
    }

    @Test
    void reportForNonCompletedReturns409() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(eq(id), eq("user-a"))).thenReturn(withStatus(id, "user-a", AnalysisStatus.PROCESSING));

        mvc.perform(get("/api/analysis/{id}/report.pdf", id)
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isConflict());
    }

    @Test
    void reportForForeignUserReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(eq(id), eq("user-b"))).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mvc.perform(get("/api/analysis/{id}/report.pdf", id)
                        .with(jwt().jwt(j -> j.subject("user-b")).authorities(userRole())))
                .andExpect(status().isNotFound());
    }
}
