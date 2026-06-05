package com.deepfake.fileservice.controller;

import com.deepfake.fileservice.dto.FileMetadataResponse;
import com.deepfake.fileservice.dto.PresignResponse;
import com.deepfake.fileservice.security.AuthenticatedUser;
import com.deepfake.fileservice.security.CurrentUser;
import com.deepfake.fileservice.service.FileMetadataService;
import com.deepfake.fileservice.service.PresignService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Operations on an existing file by id (metadata; presign and delete follow). */
@RestController
@RequestMapping("/api/files")
@PreAuthorize("hasRole('USER')")
@RequiredArgsConstructor
public class FileController {

    private final FileMetadataService metadataService;
    private final PresignService presignService;

    @Operation(summary = "Get file metadata")
    @ApiResponse(responseCode = "200", description = "Metadata of the owned file")
    @ApiResponse(responseCode = "404", description = "Missing, soft-deleted, or not owned (IDOR)")
    @GetMapping("/{id}/metadata")
    public FileMetadataResponse metadata(@PathVariable UUID id, @CurrentUser AuthenticatedUser user) {
        return metadataService.metadata(id, user.id());
    }

    @Operation(summary = "Get a short-lived presigned download URL")
    @ApiResponse(responseCode = "200", description = "Presigned URL (1 h) on a browser-reachable host")
    @ApiResponse(responseCode = "404", description = "Missing, soft-deleted, or not owned (IDOR)")
    @GetMapping("/{id}/presign")
    public PresignResponse presign(@PathVariable UUID id, @CurrentUser AuthenticatedUser user) {
        return presignService.presign(id, user.id());
    }

    @Operation(summary = "Soft-delete a file")
    @ApiResponse(responseCode = "204", description = "Soft-deleted (object retained)")
    @ApiResponse(responseCode = "404", description = "Missing, already deleted, or not owned (IDOR)")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @CurrentUser AuthenticatedUser user) {
        metadataService.softDelete(id, user.id());
    }
}
