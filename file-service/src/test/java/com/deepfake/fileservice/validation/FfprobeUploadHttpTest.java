package com.deepfake.fileservice.validation;

import com.deepfake.fileservice.controller.FileUploadController;
import com.deepfake.fileservice.exception.GlobalExceptionHandler;
import com.deepfake.fileservice.exception.UploadExceptionHandler;
import com.deepfake.fileservice.security.CurrentUserArgumentResolver;
import com.deepfake.fileservice.service.FileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FfprobeUploadHttpTest {
    @TempDir Path spool;
    private final FileStorageService storage = mock(FileStorageService.class);
    private static final String VALID = "{\"streams\":[{\"index\":0}],\"format\":{\"format_name\":\"mp4\"}}";

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    private FfprobeMediaProbe probe(String mode, String output, int limit, Duration timeout) throws Exception {
        return new FfprobeMediaProbe(limit, List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                Path.of(FfprobeMediaProbeProcessTest.Child.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()).toString(),
                FfprobeMediaProbeProcessTest.Child.class.getName(), mode, spool.resolve("child.pid").toString(),
                Base64.getEncoder().encodeToString(output.getBytes(StandardCharsets.UTF_8))), timeout);
    }

    private void upload(MediaProbe probe, int status, String code) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("test").header("alg", "none").subject("alice").build(), List.of()));
        var controller = new FileUploadController(storage, new FileValidator(probe));
        ReflectionTestUtils.setField(controller, "spool", spool.toString());
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new UploadExceptionHandler(), new GlobalExceptionHandler()).build();
        var result = mvc.perform(multipart("/api/files/upload").file(new MockMultipartFile(
                        "file", "clip.mp4", "video/mp4", HexFormat.of().parseHex(
                        "0000001c6674797069736f6d0000020069736f6d69736f326d703431"))))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
        if (status == 503) result.andExpect(header().string("Retry-After", "5"));
        else result.andExpect(header().doesNotExist("Retry-After"));
        try (var files = Files.list(spool)) {
            assertThat(files.filter(file -> file.getFileName().toString().startsWith("upload-")).toList()).isEmpty();
        }
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"stall", "eof"})
    void timeoutReturnsRetryable503AndDeletesUpload(String mode) throws Exception {
        upload(probe(mode, VALID, 1024, Duration.ofSeconds(1)), 503, "SERVICE_UNAVAILABLE");
    }

    @Test void startupIoFailureReturnsRetryable503() throws Exception {
        upload(new FfprobeMediaProbe(1024, List.of(spool.resolve("missing-probe").toString()),
                Duration.ofSeconds(1)), 503, "SERVICE_UNAVAILABLE");
    }

    @Test void nonzeroExitStillReturns422() throws Exception {
        upload(probe("fail", VALID, 1024, Duration.ofSeconds(3)), 422, "INVALID_FILE");
    }

    @Test void malformedOutputStillReturns422() throws Exception {
        upload(probe("output", "{", 1024, Duration.ofSeconds(3)), 422, "INVALID_FILE");
    }

    @Test void outputOverflowStillReturns422() throws Exception {
        upload(probe("stall", VALID, 8, Duration.ofSeconds(3)), 422, "INVALID_FILE");
    }
}
