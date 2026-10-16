package com.deepfake.fileservice.controller;

import com.deepfake.fileservice.dto.FileUploadResponse;
import com.deepfake.fileservice.entity.FileMetadata;
import com.deepfake.fileservice.service.FileStorageService;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
@PreAuthorize("hasRole('USER')")
@RequiredArgsConstructor
public class FileUploadController {
    private final FileStorageService storageService;
    private final FileValidator fileValidator;
    @Value("${spring.servlet.multipart.location:${java.io.tmpdir}/deepfake-uploads}") String spool;

    @Operation(summary = "Upload a media file for analysis")
    @ApiResponse(responseCode = "200", description = "Stored; returns fileId, fileKey, size, detected mimetype")
    @ApiResponse(responseCode = "400", description = "Missing file part or invalid multipart request (VALIDATION_FAILED)")
    @ApiResponse(responseCode = "405", description = "Method not allowed; Allow header lists supported methods")
    @ApiResponse(responseCode = "406", description = "Requested representation unavailable; empty body",
            content = @io.swagger.v3.oas.annotations.media.Content)
    @ApiResponse(responseCode = "413", description = "File exceeds 500 MiB or multipart request exceeds 501 MiB (PAYLOAD_TOO_LARGE)")
    @ApiResponse(responseCode = "409", description = "STORAGE_QUOTA_EXCEEDED: owner's storage quota exhausted (default 5 GB)")
    @ApiResponse(responseCode = "429", description = "UPLOAD_USER_LIMIT: per-user upload capacity exhausted on this replica; Retry-After: 5")
    @ApiResponse(responseCode = "503", description = "SERVICE_UNAVAILABLE: replica full or ffprobe timeout, interruption or I/O failure; Retry-After: 5")
    @ApiResponse(responseCode = "422", description = "INVALID_FILE with message prefix: UNSUPPORTED_MEDIA, INVALID_MEDIA_DURATION, MEDIA_TOO_LONG or MEDIA_RESOLUTION_TOO_HIGH")
    @PostMapping("/upload")
    public FileUploadResponse upload(@CurrentUser AuthenticatedUser user,
                                     @RequestParam MultipartFile file) throws IOException {
        UUID fileId = UUID.randomUUID();
        Path tempFile = Files.createTempFile(Path.of(spool), "upload-", ".media");
        try {
            long size = file.getSize();
            // The File overload uses Part.write (Tomcat rename), avoiding a second media copy.
            file.transferTo(tempFile.toFile());
            FileValidator.Result validated = fileValidator.validate(tempFile);
            String key = fileId + extension(validated.mimetype());
            storageService.store(FileMetadata.builder().fileId(fileId).objectKey(key).userId(user.id())
                    .originalName(safeName(file.getOriginalFilename())).mimetype(validated.mimetype())
                    .sizeBytes(size).durationSeconds(validated.durationSeconds()).build(), tempFile);
            return new FileUploadResponse(fileId.toString(), key, size, validated.mimetype());
        } finally {
            Files.deleteIfExists(tempFile);
        }

    }

    private static String safeName(String name) {
        if (name == null) return null;
        return name.codePoints().filter(c -> !Character.isISOControl(c)).limit(512)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    private static String extension(String mime) {
        return switch (mime) {
            case "video/mp4" -> ".mp4";
            case "video/quicktime" -> ".mov";
            case "video/x-msvideo" -> ".avi";
            case "audio/vnd.wave", "audio/x-wav", "audio/wav" -> ".wav";
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/x-flac", "audio/flac" -> ".flac";
            default -> throw new IllegalArgumentException("Unsupported validated MIME");
        };
    }
}
