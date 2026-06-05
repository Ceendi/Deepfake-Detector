package com.deepfake.fileservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.deepfake.fileservice.dto.FileMetadataResponse;
import com.deepfake.fileservice.entity.FileMetadata;
import com.deepfake.fileservice.repository.FileMetadataRepository;

/**
 * Service-layer IDOR guard: another user's file (or a soft-deleted one) is indistinguishable from a
 * missing one — both surface as 404, never 403.
 */
@ExtendWith(MockitoExtension.class)
class FileMetadataServiceTest {

    @Mock
    FileMetadataRepository repository;
    @InjectMocks
    FileMetadataService service;

    private final UUID id = UUID.randomUUID();

    @Test
    void returnsMetadataForOwner() {
        FileMetadata m = FileMetadata.builder().fileId(id).userId("alice").originalName("clip.mp4")
                .mimetype("video/mp4").sizeBytes(123L).durationSeconds(4.2).build();
        when(repository.findByFileIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(m));

        FileMetadataResponse r = service.metadata(id, "alice");

        assertThat(r.fileId()).isEqualTo(id.toString());
        assertThat(r.name()).isEqualTo("clip.mp4");
        assertThat(r.size()).isEqualTo(123L);
        assertThat(r.duration()).isEqualTo(4.2);
        assertThat(r.mimetype()).isEqualTo("video/mp4");
    }

    @Test
    void rejectsCrossUserAccessAs404() {
        FileMetadata m = FileMetadata.builder().fileId(id).userId("alice").build();
        when(repository.findByFileIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(m));

        assertThatThrownBy(() -> service.metadata(id, "bob"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
    }

    @Test
    void missingReturns404() {
        when(repository.findByFileIdAndDeletedAtIsNull(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.metadata(id, "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
    }
}
