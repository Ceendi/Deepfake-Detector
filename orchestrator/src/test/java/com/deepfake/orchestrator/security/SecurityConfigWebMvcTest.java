package com.deepfake.orchestrator.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.deepfake.orchestrator.config.SecurityConfig;
import com.deepfake.orchestrator.controller.AnalysisController;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.service.AnalysisService;

/**
 * Slice test of the orchestrator security wiring (no Keycloak, no DB): anonymous request is
 * rejected with 401, an authenticated JWT reaches the controller. JwtDecoder is mocked so the
 * filter chain builds without resolving the real issuer; jwt() seeds the context directly.
 */
@WebMvcTest(AnalysisController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class})
class SecurityConfigWebMvcTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AnalysisService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void getReturns401WithoutToken() throws Exception {
        mvc.perform(get("/api/analysis/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getReturns200WithValidJwt() throws Exception {
        when(service.get(any())).thenReturn(new Analysis());

        mvc.perform(get("/api/analysis/{id}", UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk());
    }
}
