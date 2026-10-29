package com.deepfake.fileservice.controller;

import com.deepfake.fileservice.exception.GlobalExceptionHandler;
import com.deepfake.fileservice.exception.StorageQuotaExceededException;
import com.deepfake.fileservice.exception.UploadExceptionHandler;
import com.deepfake.fileservice.security.CurrentUserArgumentResolver;
import com.deepfake.fileservice.service.FileStorageService;
import com.deepfake.fileservice.validation.FileValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UploadDependencyFailureTest {
    @TempDir Path spool;
    @BeforeEach void login() {
        var jwt = Jwt.withTokenValue("token").header("alg", "none").subject("alice").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
    @AfterEach void logout() {
        SecurityContextHolder.clearContext();
    }

    @Test void permanentStorageErrorsKeep500AndQuotaMessageHasNoConfiguredNumbers() {
        var handler = new UploadExceptionHandler();
        S3Exception error = (S3Exception) S3Exception.builder().statusCode(403).message("denied").build();
        var response = handler.storageFailure(error, new MockHttpServletRequest());
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getHeaders().getFirst("Retry-After")).isNull();
        assertThat(new StorageQuotaExceededException())
                .hasMessageNotContaining("5 GB").hasMessageNotContaining("72 h");
    }

    @Test void temporaryDependenciesReturnRetryable503AndAlwaysRemoveSpool() throws Exception {
        for (RuntimeException failure : List.of(SdkClientException.create("offline"),
                S3Exception.builder().statusCode(503).message("offline").build(),
                new CannotCreateTransactionException("offline"), new DataAccessResourceFailureException("offline"),
                new QueryTimeoutException("offline"))) {
            var validator = mock(FileValidator.class);
            when(validator.validate(any())).thenThrow(failure);
            var controller = new FileUploadController(mock(FileStorageService.class), validator);
            ReflectionTestUtils.setField(controller, "spool", spool.toString());
            var mvc = MockMvcBuilders.standaloneSetup(controller)
                    .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                    .setControllerAdvice(new UploadExceptionHandler(), new GlobalExceptionHandler()).build();
            mvc.perform(multipart("/api/files/upload")
                    .file(new MockMultipartFile("file", "clip.mp4", "video/mp4", new byte[]{1})))
                    .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "5"))
                    .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
            try (var files = Files.list(spool)) {
                assertThat(files.toList()).isEmpty();
            }
        }
    }
}
