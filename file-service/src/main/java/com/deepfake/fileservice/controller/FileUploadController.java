package com.deepfake.fileservice.controller;

import com.deepfake.fileservice.dto.FileUploadResponse;
import com.deepfake.fileservice.entity.FileMetadata;
import com.deepfake.fileservice.repository.FileMetadataRepository;
import com.deepfake.fileservice.security.AuthenticatedUser;
import com.deepfake.fileservice.security.CurrentUser;
import com.deepfake.fileservice.validation.FileValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
@PreAuthorize("hasRole('USER')")
@RequiredArgsConstructor
public class FileUploadController {
    private final S3Client s3Client;
    private final FileMetadataRepository metadataRepository;
    private final FileValidator fileValidator;
    @Value("${storage.bucket}") String bucket;

    @Operation(summary = "Upload a media file for analysis")
    @ApiResponse(responseCode = "200", description = "Stored; returns fileId, fileKey, size, detected mimetype")
    @ApiResponse(responseCode = "413", description = "File exceeds the 500 MB limit")
    @ApiResponse(responseCode = "422", description = "Not a whitelisted A/V container (magic bytes + ffprobe)")
    @PostMapping("/upload")
    public FileUploadResponse upload(@CurrentUser AuthenticatedUser user,
                                     @RequestParam MultipartFile file) throws IOException {
        UUID fileId = UUID.randomUUID();
        String key = fileId + "_" + file.getOriginalFilename();

        Path tempFile = Files.createTempFile("upload-", "-" + file.getOriginalFilename());
        FileValidator.Result validated;
        try {
            file.transferTo(tempFile);
            validated = fileValidator.validate(tempFile);
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket).key(key)
                            .contentType(validated.mimetype())
                            .contentLength(file.getSize())
                            .metadata(Map.of("user-id", user.id())) // stamp owner (x-amz-meta-user-id)
                            .build(),
                    RequestBody.fromFile(tempFile)
            );
        } finally {
            Files.deleteIfExists(tempFile);
        }

        // S3 first, then DB. If the row write fails the object is orphaned in S3 but invisible to
        // the API (every lookup goes through this row); S3CleanupService reclaims it once it
        // outlives the orphan-min-age window.
        metadataRepository.save(FileMetadata.builder()
                .fileId(fileId).objectKey(key).userId(user.id())
                .originalName(file.getOriginalFilename()).mimetype(validated.mimetype())
                .sizeBytes(file.getSize()).durationSeconds(validated.durationSeconds()).build());

        return new FileUploadResponse(fileId.toString(), key, file.getSize(), validated.mimetype());
    }
}
