package com.deepfake.fileservice.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.deepfake.fileservice.exception.InvalidFileException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Real ffprobe and Tika tests are required: missing ffmpeg fails rather than silently skipping. */
class FfprobeMediaProbeIntegrationTest {
    private static final int OUTPUT_LIMIT = 65536;
    private final FfprobeMediaProbe probe = new FfprobeMediaProbe(OUTPUT_LIMIT);
    private final FileValidator validator = new FileValidator(probe);
    @TempDir Path tmp;

    @BeforeAll
    static void requireFfprobeAndFfmpeg() throws Exception {
        for (String binary : List.of("ffprobe", "ffmpeg")) {
            Process process = new ProcessBuilder(binary, "-version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).as(binary + " is required for real media tests").isTrue();
                assertThat(process.exitValue()).isZero();
            } finally {
                process.destroyForcibly().waitFor();
            }
        }
    }

    @Test
    void rejectsRealVideoAbove4kPixelBudget() throws Exception {
        Path media = tmp.resolve("above4k.mp4");
        runToFile(List.of("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
                "color=c=black:s=4096x2160:r=1", "-frames:v", "1", "-c:v", "mpeg4", media.toString()),
                tmp.resolve("generation.log"));
        assertThatThrownBy(() -> validator.validate(media)).isInstanceOf(InvalidFileException.class)
                .hasMessage("[MEDIA_RESOLUTION_TOO_HIGH] Rozdzielczość nagrania jest za wysoka (maks. 4K).");
    }

    @Test
    void acceptsAudioWithLargeCoverArt() throws Exception {
        Path media = tmp.resolve("cover.mp3");
        runToFile(List.of("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=d=1", "-f", "lavfi", "-i",
                "color=c=black:s=3000x3000", "-frames:v", "1", "-map", "0:a", "-map", "1:v", "-c:v", "mjpeg",
                "-disposition:v", "attached_pic", media.toString()), tmp.resolve("generation.log"));
        assertThat(validator.validate(media).mimetype()).isIn(FileValidator.MIME_WHITELIST);
    }

    @Test
    void probesValidWavAndReportsFormatAndDuration() throws IOException {
        Path wav = tmp.resolve("tone.wav");
        Files.write(wav, wav(new byte[0]));
        FileValidator.Result result = validator.validate(wav);
        assertThat(result.mimetype()).isEqualTo("audio/vnd.wave");
        assertThat(result.durationSeconds()).isCloseTo(0.1, org.assertj.core.data.Offset.offset(0.05));
    }

    @ParameterizedTest
    @CsvSource({"mp4,video/quicktime", "mov,video/quicktime", "avi,video/x-msvideo",
            "mp3,audio/mpeg", "flac,audio/x-flac"})
    void preservesRealTikaAndProbeAcceptanceForSupportedContainers(String extension, String expectedMime) throws Exception {
        Path media = tmp.resolve("media." + extension);
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-v", "error", "-y"));
        if (List.of("mp3", "flac").contains(extension)) {
            Path source = tmp.resolve("source.wav");
            Files.write(source, wav(new byte[0]));
            command.addAll(List.of("-i", source.toString()));
        } else {
            command.addAll(List.of("-f", "lavfi", "-i", "color=c=black:s=16x16:r=10",
                    "-t", "0.1", "-c:v", "mpeg4"));
        }
        command.add(media.toString());
        runToFile(command, tmp.resolve("generation.log"));
        FileValidator.Result result = validator.validate(media);
        assertThat(result.mimetype()).isEqualTo(expectedMime);
        assertThat(result.durationSeconds()).isPositive();
    }

    @Test
    void detectsWavNamedMp4FromBytes() throws IOException {
        Path media = tmp.resolve("audio-named.mp4");
        Files.write(media, wav(new byte[0]));

        FileValidator.Result result = validator.validate(media);

        assertThat(result.mimetype()).isEqualTo("audio/vnd.wave");
        assertThat(result.durationSeconds()).isEqualTo(0.1);
    }

    @Test
    void rejectsRandomBytesNamedMp4() throws IOException {
        byte[] noise = new byte[256 * 1024];
        new java.util.Random(42).nextBytes(noise);
        Path media = tmp.resolve("noise.mp4");
        Files.write(media, noise);

        assertThatThrownBy(() -> validator.validate(media)).isInstanceOf(InvalidFileException.class)
                .hasMessageStartingWith("[UNSUPPORTED_MEDIA] unsupported media type:");
    }

    @Test
    void rejectsRealWebmEvenWhenNamedMp4() throws Exception {
        Path media = tmp.resolve("clip.mp4");
        runToFile(List.of("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
                "color=c=gray:s=16x16:r=10", "-t", "0.1", "-c:v", "libvpx-vp9", "-f", "webm",
                media.toString()), tmp.resolve("generation.log"));
        assertThat(probe.probe(media)).isPresent();

        assertThatThrownBy(() -> validator.validate(media)).isInstanceOf(InvalidFileException.class)
                .hasMessageStartingWith("[UNSUPPORTED_MEDIA] unsupported media type:");
    }

    @Test
    void ignoresLargeControlByteTagsWithoutAmplifyingRetainedOutput() throws Exception {
        byte[] comment = new byte[1024 * 1024];
        java.util.Arrays.fill(comment, (byte) 1);
        comment[comment.length - 1] = 0;
        Path media = tmp.resolve("tagged.wav");
        Files.write(media, wav(comment));

        // Negative control demonstrates that this valid upload would previously expand over 6 MiB.
        Path unrestricted = tmp.resolve("unrestricted.json");
        runToFile(List.of("ffprobe", "-v", "quiet", "-print_format", "json",
                "-show_format", "-show_streams", media.toString()), unrestricted);
        assertThat(Files.size(unrestricted)).isGreaterThan(6L * 1024 * 1024);

        Path restricted = tmp.resolve("restricted.json");
        runToFile(List.of("ffprobe", "-v", "quiet", "-print_format", "json", "-show_entries",
                "format=format_name,duration:stream=index", media.toString()), restricted);
        assertThat(Files.size(restricted)).isLessThan(1024);
        assertThat(Files.readString(restricted)).doesNotContain("tags", "comment", "\\u0001");
        assertThat(validator.validate(media).mimetype()).isIn(FileValidator.MIME_WHITELIST);
        assertThat(new FfprobeMediaProbe(1024).probe(media)).isPresent();
    }

    @Test
    void returnsEmptyForNonMediaBytes() throws IOException {
        Path junk = tmp.resolve("not-media.bin");
        Files.writeString(junk, "definitely not a media container");
        assertThat(probe.probe(junk)).isEmpty();
    }

    @Test
    void realWavDurationRespectsConfiguredLimit() throws IOException {
        Path media = tmp.resolve("duration.wav");
        Files.write(media, wav(new byte[0]));
        assertThatThrownBy(() -> new FileValidator(probe, 0.05).validate(media))
                .isInstanceOf(InvalidFileException.class).hasMessageContaining("duration");
        assertThat(new FileValidator(probe, 0.1).validate(media).durationSeconds()).isEqualTo(0.1);
    }

    private static void runToFile(List<String> command, Path output) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).as("fixture subprocess deadline").isTrue();
            assertThat(process.exitValue()).as("fixture subprocess: " + command.getFirst()).isZero();
        } finally {
            process.destroyForcibly().waitFor();
        }
    }

    /** PCM 16-bit mono, 800 silent frames at 8 kHz, with an optional WAV LIST/INFO comment. */
    private static byte[] wav(byte[] comment) {
        int metadataSize = comment.length == 0 ? 0 : 20 + comment.length;
        int dataSize = 1600;
        ByteBuffer buf = ByteBuffer.allocate(44 + dataSize + metadataSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataSize + metadataSize)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000)
                .putShort((short) 2).putShort((short) 16);
        if (metadataSize != 0) {
            buf.put("LIST".getBytes(StandardCharsets.US_ASCII)).putInt(12 + comment.length)
                    .put("INFO".getBytes(StandardCharsets.US_ASCII))
                    .put("ICMT".getBytes(StandardCharsets.US_ASCII)).putInt(comment.length).put(comment);
        }
        buf.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataSize);
        return buf.array();
    }
}
