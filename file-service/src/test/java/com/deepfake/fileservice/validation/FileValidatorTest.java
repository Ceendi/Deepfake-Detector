package com.deepfake.fileservice.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.deepfake.fileservice.validation.MediaProbe.ProbeResult;

/**
 * Unit tests for the magic-byte stage (real Tika) with a mocked ffprobe. The detected MIME — not the
 * extension — decides: non-media is rejected before ffprobe is ever consulted; a valid signature is
 * accepted only if ffprobe also confirms a whitelisted container.
 */
@ExtendWith(MockitoExtension.class)
class FileValidatorTest {

    // Minimal MP4 ftyp box (major brand "isom") — enough for Tika's magic detection.
    private static final byte[] MP4_MAGIC = {
            0x00, 0x00, 0x00, 0x1C, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D,
            0x00, 0x00, 0x02, 0x00, 0x69, 0x73, 0x6F, 0x6D, 0x69, 0x73, 0x6F, 0x32, 0x6D, 0x70, 0x34, 0x31
    };

    @Mock
    MediaProbe mediaProbe;
    @TempDir
    Path tmp;

    private FileValidator validator() {
        return new FileValidator(mediaProbe);
    }

    @Test
    void acceptsRealMediaWhenMagicAndFfprobeAgree() throws IOException {
        Path file = write("clip.mp4", MP4_MAGIC);
        when(mediaProbe.probe(any())).thenReturn(Optional.of(new ProbeResult("mov,mp4,m4a", 12.5)));

        FileValidator.Result result = validator().validate(file);

        assertThat(FileValidator.MIME_WHITELIST).contains(result.mimetype()); // whatever alias Tika emits
        assertThat(result.durationSeconds()).isEqualTo(12.5);
    }

    @Test
    void rejectsNonMediaByMagicBytesWithoutCallingFfprobe() throws IOException {
        Path file = write("evil.mp4", "this is plain text pretending to be a video".getBytes());

        assertThatThrownBy(() -> validator().validate(file))
                .isInstanceOf(InvalidFileException.class);
        verifyNoInteractions(mediaProbe);
    }

    @Test
    void rejectsWhenFfprobeFindsNoValidContainer() throws IOException {
        Path file = write("clip.mp4", MP4_MAGIC);
        when(mediaProbe.probe(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> validator().validate(file))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("not a valid media container");
    }

    @Test
    void rejectsWhenFfprobeFormatNotWhitelisted() throws IOException {
        Path file = write("clip.mp4", MP4_MAGIC);
        when(mediaProbe.probe(any())).thenReturn(Optional.of(new ProbeResult("matroska,webm", 1.0)));

        assertThatThrownBy(() -> validator().validate(file))
                .isInstanceOf(InvalidFileException.class)
                .hasMessageContaining("unsupported container format");
    }

    private Path write(String name, byte[] bytes) throws IOException {
        Path file = tmp.resolve(name);
        Files.write(file, bytes);
        return file;
    }
}
