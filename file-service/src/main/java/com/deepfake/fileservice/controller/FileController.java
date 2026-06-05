package com.deepfake.fileservice.controller;

import com.deepfake.fileservice.dto.FileMetadataResponse;
import com.deepfake.fileservice.security.AuthenticatedUser;
import com.deepfake.fileservice.security.CurrentUser;
import com.deepfake.fileservice.service.FileMetadataService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Operations on an existing file by id (metadata; presign and delete follow). */
@RestController
@RequestMapping("/api/files")
@PreAuthorize("hasRole('USER')")
@RequiredArgsConstructor
public class FileController {

    private final FileMetadataService metadataService;

    @GetMapping("/{id}/metadata")
    public FileMetadataResponse metadata(@PathVariable UUID id, @CurrentUser AuthenticatedUser user) {
        return metadataService.metadata(id, user.id());
    }
}
