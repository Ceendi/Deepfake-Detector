package com.deepfake.fileservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.deepfake.fileservice.config.SecurityConfig;
import com.deepfake.fileservice.config.WebConfig;
import com.deepfake.fileservice.exception.InvalidFileException;
import com.deepfake.fileservice.repository.FileMetadataRepository;
import com.deepfake.fileservice.security.CurrentUserArgumentResolver;
import com.deepfake.fileservice.security.JwtRoleConverter;
import com.deepfake.fileservice.validation.FileValidator;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * Uniform error contract (GlobalExceptionHandler, auto-included as @ControllerAdvice in the
 * slice): rejected upload -> 422 INVALID_FILE, unmapped path -> 404 NOT_FOUND, every error body
 * carries code + correlationId + timestamp. Twin of orchestrator's AnalysisErrorContractTest.
 */
@WebMvcTest(FileUploadController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class, WebConfig.class, CurrentUserArgumentResolver.class})
class FileErrorContractTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    S3Client s3Client;

    @MockitoBean
    FileMetadataRepository metadataRepository;

    @MockitoBean
    FileValidator fileValidator;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private static SimpleGrantedAuthority userRole() {
        return new SimpleGrantedAuthority("ROLE_USER");
    }

    private static MockMultipartFile sampleFile() {
        return new MockMultipartFile("file", "evil.mp4", "video/mp4", "MZ-not-a-video".getBytes());
    }

    @Test
    void rejectedUploadReturns422WithInvalidFileCode() throws Exception {
        when(fileValidator.validate(any()))
                .thenThrow(new InvalidFileException("Not a supported media container"));

        mvc.perform(multipart("/api/files/upload").file(sampleFile())
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"))
                .andExpect(jsonPath("$.message").value("Not a supported media container"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // Unmapped path must stay 404 (NoResourceFoundException), not fall into the catch-all 500.
    @Test
    void unmappedPathReturns404NotInternalError() throws Exception {
        mvc.perform(get("/api/files/nope/nothing")
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unexpectedFailureReturns500WithGenericBody() throws Exception {
        when(fileValidator.validate(any())).thenThrow(new IllegalStateException("internal detail"));

        mvc.perform(multipart("/api/files/upload").file(sampleFile())
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                // internal detail stays in logs, never echoed to the client
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }
}
