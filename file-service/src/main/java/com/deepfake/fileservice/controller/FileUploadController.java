package com.deepfake.fileservice.controller;

import com.deepfake.fileservice.dto.FileUploadResponse;
import com.deepfake.fileservice.security.AuthenticatedUser;
import com.deepfake.fileservice.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
@PreAuthorize("hasRole('USER')")
@RequiredArgsConstructor
public class FileUploadController {
    private final S3Client s3Client;
    @Value("${storage.bucket}") String bucket;

    @PostMapping("/upload")
    public FileUploadResponse upload(@CurrentUser AuthenticatedUser user,
                                     @RequestParam MultipartFile file) throws IOException {
        String fileId = UUID.randomUUID().toString();
        String key = fileId + "_" + file.getOriginalFilename();

        Path tempFile = Files.createTempFile("upload-", "-" + file.getOriginalFilename());
        try {
            file.transferTo(tempFile);
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket).key(key)
                            .contentType(file.getContentType())
                            .contentLength(file.getSize())
                            .metadata(Map.of("user-id", user.id())) // stamp owner (x-amz-meta-user-id)
                            .build(),
                    RequestBody.fromFile(tempFile)
            );
        } finally {
            Files.deleteIfExists(tempFile);
        }

        return new FileUploadResponse(fileId, key, file.getSize(),
                Objects.requireNonNull(file.getContentType()));
    }
}
