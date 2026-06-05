package com.deepfake.fileservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import java.time.Instant;

import com.deepfake.fileservice.config.SecurityConfig;
import com.deepfake.fileservice.config.WebConfig;
import com.deepfake.fileservice.dto.FileMetadataResponse;
import com.deepfake.fileservice.dto.PresignResponse;
import com.deepfake.fileservice.security.CurrentUserArgumentResolver;
import com.deepfake.fileservice.security.JwtRoleConverter;
import com.deepfake.fileservice.service.FileMetadataService;
import com.deepfake.fileservice.service.PresignService;

/**
 * Auth + IDOR behaviour of the metadata endpoint: anonymous -> 401, wrong role -> 403, owner -> 200,
 * another user's id -> 404. The JWT subject must reach the service as the owner id.
 */
@WebMvcTest(FileController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class, WebConfig.class, CurrentUserArgumentResolver.class})
class FileControllerSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    FileMetadataService metadataService;

    @MockitoBean
    PresignService presignService;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private static SimpleGrantedAuthority userRole() {
        return new SimpleGrantedAuthority("ROLE_USER");
    }

    @Test
    void metadataWithoutTokenReturns401() throws Exception {
        mvc.perform(get("/api/files/{id}/metadata", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void metadataWithoutUserRoleReturns403() throws Exception {
        mvc.perform(get("/api/files/{id}/metadata", UUID.randomUUID())
                        .with(jwt().jwt(j -> j.subject("user-a"))
                                .authorities(new SimpleGrantedAuthority("ROLE_GUEST"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void metadataReturns200ForOwner() throws Exception {
        UUID id = UUID.randomUUID();
        when(metadataService.metadata(eq(id), eq("user-a")))
                .thenReturn(new FileMetadataResponse(id.toString(), "clip.mp4", 123L, 4.2, "video/mp4"));

        mvc.perform(get("/api/files/{id}/metadata", id)
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value(id.toString()))
                .andExpect(jsonPath("$.name").value("clip.mp4"))
                .andExpect(jsonPath("$.mimetype").value("video/mp4"));
    }

    @Test
    void metadataReturns404ForDifferentUser() throws Exception {
        UUID id = UUID.randomUUID();
        when(metadataService.metadata(eq(id), eq("user-b")))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mvc.perform(get("/api/files/{id}/metadata", id)
                        .with(jwt().jwt(j -> j.subject("user-b")).authorities(userRole())))
                .andExpect(status().isNotFound());
    }

    @Test
    void presignReturns200ForOwner() throws Exception {
        UUID id = UUID.randomUUID();
        when(presignService.presign(eq(id), eq("user-a")))
                .thenReturn(new PresignResponse("http://localhost:8333/deepfake-uploads/" + id,
                        Instant.parse("2026-01-01T00:00:00Z")));

        mvc.perform(get("/api/files/{id}/presign", id)
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("http://localhost:8333/deepfake-uploads/" + id))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void presignReturns404ForDifferentUser() throws Exception {
        UUID id = UUID.randomUUID();
        when(presignService.presign(eq(id), eq("user-b")))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mvc.perform(get("/api/files/{id}/presign", id)
                        .with(jwt().jwt(j -> j.subject("user-b")).authorities(userRole())))
                .andExpect(status().isNotFound());
    }
}
