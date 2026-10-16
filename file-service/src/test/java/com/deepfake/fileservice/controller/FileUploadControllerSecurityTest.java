package com.deepfake.fileservice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import com.deepfake.fileservice.exception.InvalidFileException;
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
import com.deepfake.fileservice.validation.FileValidator;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Upload auth: anonymous → 401, authenticated without USER role → 403, and a valid JWT uploads
 * the object stamped with the caller's id as x-amz-meta-user-id.
 */
@WebMvcTest(FileUploadController.class)
@Import({SecurityConfig.class, JwtRoleConverter.class, WebConfig.class, CurrentUserArgumentResolver.class,
        com.deepfake.fileservice.service.FileStorageService.class})
class FileUploadControllerSecurityTest {

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

    @MockitoBean
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @MockitoBean
    org.springframework.transaction.PlatformTransactionManager transactions;

    private static SimpleGrantedAuthority userRole() {
        return new SimpleGrantedAuthority("ROLE_USER");
    }

    private static MockMultipartFile sampleFile() {
        return new MockMultipartFile("file", "test.mp4", "video/mp4", "data".getBytes());
    }

    @Test
    void untrustedNameNeverBecomesADiskOrObjectPath() throws Exception {
        String name = "folder/" + "界".repeat(100) + "\u0000.mp4";
        when(fileValidator.validate(any())).thenReturn(new FileValidator.Result("video/mp4", 1.0));
        mvc.perform(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", name, "video/mp4", "data".getBytes()))
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileKey").value(org.hamcrest.Matchers.matchesPattern(
                        "[0-9a-f-]{36}\\.mp4")));
    }

    @Test
    void uploadMovesTheServletSpoolInsteadOfCopyingIt() throws Exception {
        AtomicReference<Boolean> moved = new AtomicReference<>(false);
        MockMultipartFile file = new MockMultipartFile("file", "test.mp4", "video/mp4", "data".getBytes()) {
            @Override
            public void transferTo(java.io.File destination) throws IOException {
                moved.set(true);
                super.transferTo(destination);
            }
        };
        when(fileValidator.validate(any())).thenReturn(new FileValidator.Result("video/mp4", 1.0));
        mvc.perform(multipart("/api/files/upload").file(file)
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isOk());
        assertThat(moved.get()).isTrue();
    }

    @Test
    void quotaReturns409WithNeutralPolishMessageBeforeS3() throws Exception {
        when(fileValidator.validate(any())).thenReturn(new FileValidator.Result("video/mp4", 1.0));
        when(metadataRepository.storedBytes("user-a")).thenReturn(5368709120L);
        mvc.perform(multipart("/api/files/upload").file(sampleFile())
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORAGE_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.message").value(
                        "Przekroczono limit miejsca na pliki (5 GB). Usuń stare analizy – miejsce zwalnia się do 72 h po usunięciu."));
        verifyNoInteractions(s3Client);
    }

    @Test
    void originalNameIsBoundedAndHasNoControlCharacters() throws Exception {
        when(fileValidator.validate(any())).thenReturn(new FileValidator.Result("video/mp4", 1.0));
        String name = "界".repeat(600) + "\u0000\r\n.mp4";
        mvc.perform(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", name, "video/mp4", "data".getBytes()))
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isOk());
        var saved = ArgumentCaptor.forClass(com.deepfake.fileservice.entity.FileMetadata.class);
        verify(metadataRepository).save(saved.capture());
        assertThat(saved.getValue().getOriginalName()).isEqualTo("界".repeat(512));
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
        when(fileValidator.validate(any())).thenReturn(new FileValidator.Result("video/mp4", 12.5));

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
    void invalidProbeResponseReturns422AndDeletesUpload() throws Exception {
        AtomicReference<Path> upload = new AtomicReference<>();
        when(fileValidator.validate(any())).thenAnswer(invocation -> {
            upload.set(invocation.getArgument(0));
            throw new InvalidFileException("not a valid media container");
        });
        mvc.perform(multipart("/api/files/upload").file(sampleFile())
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"));
        assertThat(upload.get()).doesNotExist();
        verifyNoInteractions(s3Client, metadataRepository);
    }

    @Test
    void probeIoFailureReturns500AndDeletesUpload() throws Exception {
        AtomicReference<Path> upload = new AtomicReference<>();
        when(fileValidator.validate(any())).thenAnswer(invocation -> {
            upload.set(invocation.getArgument(0));
            throw new IOException("probe pipe failure");
        });
        mvc.perform(multipart("/api/files/upload").file(sampleFile())
                        .with(jwt().jwt(j -> j.subject("user-a")).authorities(userRole())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        assertThat(upload.get()).doesNotExist();
        verifyNoInteractions(s3Client, metadataRepository);
    }

    @Test
    void openApiDocsPathRequiresAuthentication() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs").with(jwt()))
                .andExpect(status().isNotFound());
    }
}
