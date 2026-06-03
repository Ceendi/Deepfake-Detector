package com.deepfake.orchestrator.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
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
import com.deepfake.orchestrator.security.CurrentUserArgumentResolver;
import com.deepfake.orchestrator.security.JwtRoleConverter;
import com.deepfake.orchestrator.service.AnalysisService;

/**
 * List endpoint + uniform error contract (GlobalExceptionHandler, auto-included as
 * @ControllerAdvice in the slice): list returns only the caller's analyses, validation yields a
 * 400 body with per-field errors, an IDOR 404 carries the NOT_FOUND error code.
 */
@WebMvcTest(AnalysisController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class, WebConfig.class, CurrentUserArgumentResolver.class})
class AnalysisErrorContractTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AnalysisService service;

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
    void listReturns200WithUsersAnalyses() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.list(eq("user-a"))).thenReturn(List.of(sample(id, "user-a")));

        mvc.perform(get("/api/analysis")
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].userId").value("user-a"));
    }

    @Test
    void validationReturns400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/analysis")
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"\",\"fileKey\":\"key-1\",\"type\":\"VIDEO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.fileId").exists());
    }

    @Test
    void idorReturns404WithErrorCode() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(eq(id), eq("user-a")))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mvc.perform(get("/api/analysis/{id}", id)
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
