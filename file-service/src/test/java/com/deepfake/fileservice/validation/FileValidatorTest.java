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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.deepfake.fileservice.exception.InvalidFileException;
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

    @Test void mediaErrorsHaveStableCodesAndKeepLegacyText() throws IOException {
        Path file = write("codes.mp4", MP4_MAGIC);
        when(mediaProbe.probe(any())).thenReturn(Optional.of(new ProbeResult("mp4", 1801.0)));
        assertThatThrownBy(() -> validator().validate(file))
                .hasMessageStartingWith("[MEDIA_TOO_LONG] media duration exceeds");
        when(mediaProbe.probe(any())).thenReturn(Optional.of(new ProbeResult("webm", 1.0)));
        assertThatThrownBy(() -> validator().validate(file))
                .hasMessageStartingWith("[UNSUPPORTED_MEDIA] unsupported container format");
        when(mediaProbe.probe(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> validator().validate(file))
                .hasMessageStartingWith("[UNSUPPORTED_MEDIA] not a valid media container");
        when(mediaProbe.probe(any())).thenReturn(Optional.of(new ProbeResult("mp4", Double.NaN)));
        assertThatThrownBy(() -> validator().validate(file))
                .hasMessageStartingWith("[INVALID_MEDIA_DURATION] invalid media duration");
        assertThatThrownBy(() -> validator().validate(write("text", new byte[] {1, 2, 3})))
                .hasMessageStartingWith("[UNSUPPORTED_MEDIA] unsupported media type");
    }

    @Test
    void rejectsResolutionAbove4kAndAcceptsBothOrientationsAtBoundary() throws IOException {
        Path file = write("resolution.mp4", MP4_MAGIC);
        when(mediaProbe.probe(any())).thenReturn(Optional.of(new ProbeResult("mp4", 1.0, 8294401)));
        assertThatThrownBy(() -> validator().validate(file)).isInstanceOf(InvalidFileException.class)
                .hasMessage("[MEDIA_RESOLUTION_TOO_HIGH] Rozdzielczość nagrania jest za wysoka (maks. 4K).");
        when(mediaProbe.probe(any())).thenReturn(Optional.of(new ProbeResult("mp4", 1.0, 3840L * 2160)));
        assertThat(validator().validate(file)).isNotNull();
        when(mediaProbe.probe(any())).thenReturn(Optional.of(new ProbeResult("mp4", 1.0, 2160L * 3840)));
        assertThat(validator().validate(file)).isNotNull();
        assertThatThrownBy(() -> new FileValidator(mediaProbe, 1800, 100).validate(file))
                .isInstanceOf(InvalidFileException.class);
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

    @Test
    void rejectsMediaOverThirtyMinutes() throws IOException {
        Path file = write("long.mp4", MP4_MAGIC);
        when(mediaProbe.probe(any())).thenReturn(Optional.of(new ProbeResult("mp4", 1800.001)));
        assertThatThrownBy(() -> validator().validate(file))
                .isInstanceOf(InvalidFileException.class).hasMessageContaining("duration");
    }

    @Test
    void acceptsMediaAtDurationLimit() throws IOException {
        Path file = write("boundary.mp4", MP4_MAGIC);
        when(mediaProbe.probe(any())).thenReturn(Optional.of(new ProbeResult("mp4", 1800.0)));
        assertThat(validator().validate(file).durationSeconds()).isEqualTo(1800.0);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsInvalidDuration(double duration) throws IOException {
        Path file = write("invalid.mp4", MP4_MAGIC);
        when(mediaProbe.probe(any())).thenReturn(Optional.of(new ProbeResult("mp4", duration)));
        assertThatThrownBy(() -> validator().validate(file)).isInstanceOf(InvalidFileException.class);
    }

    @Test
    void preservesUnknownDurationAcceptance() throws IOException {
        Path file = write("unknown.mp4", MP4_MAGIC);
        when(mediaProbe.probe(any())).thenReturn(Optional.of(new ProbeResult("mp4", null)));
        assertThat(validator().validate(file).durationSeconds()).isNull();
    }

    @Test
    void appliesConfiguredDurationLimit() throws IOException {
        Path file = write("configured.mp4", MP4_MAGIC);
        when(mediaProbe.probe(any())).thenReturn(Optional.of(new ProbeResult("mp4", 60.1)));
        assertThatThrownBy(() -> new FileValidator(mediaProbe, 60).validate(file))
                .isInstanceOf(InvalidFileException.class);
        assertThat(new FileValidator(mediaProbe, 61).validate(file).durationSeconds()).isEqualTo(60.1);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsInvalidConfiguredLimit(double limit) {
        assertThatThrownBy(() -> new FileValidator(mediaProbe, limit))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Path write(String name, byte[] bytes) throws IOException {
        Path file = tmp.resolve(name);
        Files.write(file, bytes);
        return file;
    }
}
