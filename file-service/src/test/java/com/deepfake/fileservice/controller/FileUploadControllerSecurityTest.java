package com.deepfake.fileservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import com.deepfake.fileservice.repository.FileMetadataRepository;
import com.deepfake.fileservice.security.CurrentUserArgumentResolver;
import com.deepfake.fileservice.security.JwtRoleConverter;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Upload auth: anonymous → 401, authenticated without USER role → 403, and a valid JWT uploads
 * the object stamped with the caller's id as x-amz-meta-user-id.
 */
@WebMvcTest(FileUploadController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class, WebConfig.class, CurrentUserArgumentResolver.class})
class FileUploadControllerSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    S3Client s3Client;

    @MockitoBean
    FileMetadataRepository metadataRepository;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private static SimpleGrantedAuthority userRole() {
        return new SimpleGrantedAuthority("ROLE_USER");
    }

    private static MockMultipartFile sampleFile() {
        return new MockMultipartFile("file", "test.mp4", "video/mp4", "data".getBytes());
    }

    @Test
    void uploadWithoutTokenReturns401() throws Exception {
        mvc.perform(multipart("/api/files/upload").file(sampleFile()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uploadWithoutUserRoleReturns403() throws Exception {
        mvc.perform(multipart("/api/files/upload").file(sampleFile())
                        .with(jwt().jwt(j -> j.subject("user-a"))
                                .authorities(new SimpleGrantedAuthority("ROLE_GUEST"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadStampsJwtSubjectAsOwner() throws Exception {
        mvc.perform(multipart("/api/files/upload").file(sampleFile())
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileKey").exists())
                .andExpect(jsonPath("$.mimetype").value("video/mp4"));

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().metadata()).containsEntry("user-id", "user-a");
    }

    @Test
    void openApiDocsPathIsPublic() throws Exception {
        // Security must not reject the docs path with 401 (no springdoc handler in the slice,
        // so the exact downstream status is irrelevant — only "not blocked by auth" matters).
        mvc.perform(get("/v3/api-docs"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(401));
    }
}
