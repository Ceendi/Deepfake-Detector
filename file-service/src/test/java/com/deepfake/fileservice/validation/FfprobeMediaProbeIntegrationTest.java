package com.deepfake.fileservice.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.deepfake.fileservice.validation.MediaProbe.ProbeResult;

/**
 * Exercises the real ffprobe subprocess against a hand-built minimal WAV. Skipped (assumption) where
 * the ffprobe binary is absent — the file-service Docker image ships ffmpeg, and CI runners need it
 * too; locally it relies on ffprobe being on PATH.
 */
class FfprobeMediaProbeIntegrationTest {

    private final FfprobeMediaProbe probe = new FfprobeMediaProbe();
    @TempDir
    Path tmp;

    @BeforeAll
    static void requireFfprobe() {
        assumeTrue(ffprobeAvailable(), "ffprobe not on PATH — skipping real-probe test");
    }

    @Test
    void probesValidWavAndReportsFormatAndDuration() throws IOException {
        Path wav = tmp.resolve("tone.wav");
        Files.write(wav, minimalWav(8000, 800)); // 800 samples @ 8 kHz = 0.1 s

        Optional<ProbeResult> result = probe.probe(wav);

        assertThat(result).isPresent();
        assertThat(result.get().formatName()).contains("wav");
        assertThat(result.get().durationSeconds()).isCloseTo(0.1, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void returnsEmptyForNonMediaBytes() throws IOException {
        Path junk = tmp.resolve("not-media.bin");
        Files.write(junk, "definitely not a media container".getBytes(StandardCharsets.UTF_8));

        assertThat(probe.probe(junk)).isEmpty();
    }

    private static boolean ffprobeAvailable() {
        try {
            return new ProcessBuilder("ffprobe", "-version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** PCM 16-bit mono WAV with {@code samples} silent frames at {@code sampleRate} Hz. */
    private static byte[] minimalWav(int sampleRate, int samples) {
        int bitsPerSample = 16;
        int blockAlign = bitsPerSample / 8; // mono
        int byteRate = sampleRate * blockAlign;
        int dataSize = samples * blockAlign;
        ByteBuffer buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataSize)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1) // PCM, mono
                .putInt(sampleRate).putInt(byteRate)
                .putShort((short) blockAlign).putShort((short) bitsPerSample);
        buf.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataSize);
        return buf.array(); // data stays zero-filled (silence)
    }
}
