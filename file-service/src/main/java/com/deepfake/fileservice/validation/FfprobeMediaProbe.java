package com.deepfake.fileservice.validation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.deepfake.fileservice.exception.MediaProbeUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs ffprobe without a shell, retaining only bounded container and stream information. */
@Slf4j
@Component
public class FfprobeMediaProbe implements MediaProbe {

    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(5);
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final int maxOutputBytes;
    private final List<String> commandPrefix;
    private final Duration timeout;

    @Autowired
    public FfprobeMediaProbe(@Value("${media.probe.max-output-bytes:65536}") int maxOutputBytes) {
        this(maxOutputBytes, List.of("ffprobe"), Duration.ofSeconds(10));
    }

    // Alternate subprocess and deadline for tests that exercise actual child-process lifecycle.
    FfprobeMediaProbe(int maxOutputBytes, List<String> commandPrefix, Duration timeout) {
        if (maxOutputBytes <= 0 || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Probe output limit and timeout must be positive");
        }
        this.maxOutputBytes = maxOutputBytes;
        this.commandPrefix = List.copyOf(commandPrefix);
        this.timeout = timeout;
    }

    @Override
    public Optional<ProbeResult> probe(Path file) throws IOException {
        List<String> cmd = new ArrayList<>(commandPrefix);
        cmd.addAll(List.of("-v", "quiet", "-protocol_whitelist", "file", "-print_format", "json", "-show_entries",
                "format=format_name,duration:stream=index,codec_type,width,height:stream_disposition=attached_pic", file.toAbsolutePath().toString()));
        Process process;
        try {
            process = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            log.warn("Could not start ffprobe");
            throw new MediaProbeUnavailableException(e);
        }
        ExecutorService reader = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("ffprobe-output-" + process.pid()).factory());
        Future<byte[]> stdout = reader.submit(() -> readBounded(process.getInputStream()));
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            // Observe overflow immediately, even when the producer keeps running or stalls.
            byte[] output = stdout.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) {
                throw new TimeoutException();
            }
            return process.exitValue() == 0 ? parse(output) : Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("ffprobe interrupted");
            throw new MediaProbeUnavailableException(e);
        } catch (TimeoutException e) {
            log.warn("ffprobe timed out");
            throw new MediaProbeUnavailableException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof OutputLimitException) {
                log.warn("ffprobe output exceeded {} bytes", maxOutputBytes);
                return Optional.empty();
            }
            if (e.getCause() instanceof IOException) {
                log.warn("Could not read ffprobe output");
                throw new MediaProbeUnavailableException(e.getCause());
            }
            throw new IOException("Could not read ffprobe output", e.getCause());
        } finally {
            cleanup(process, stdout, reader);
        }
    }

    private void cleanup(Process process, Future<byte[]> stdout, ExecutorService reader) {
        // Kill before closing pipes: close can otherwise wait for a blocked read to finish.
        process.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + CLEANUP_TIMEOUT.toNanos();
        boolean exited = false;
        try {
            while (System.nanoTime() < deadline) {
                try {
                    exited = process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS);
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (TimeoutException e) {
                    break;
                }
            }
            if (exited) {
                try (InputStream ignored = process.getInputStream()) {
                    process.getOutputStream().close();
                    process.getErrorStream().close();
                } catch (IOException e) {
                    log.warn("Could not close ffprobe pipes");
                }
            } else {
                // A pipe close may block while a live process still owns stdout.
                log.warn("ffprobe did not exit within cleanup budget");
            }
        } finally {
            stdout.cancel(true);
            reader.shutdownNow();
            boolean stopped = reader.isTerminated();
            while (!stopped && System.nanoTime() < deadline) {
                try {
                    stopped = reader.awaitTermination(remaining(deadline), TimeUnit.NANOSECONDS);
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (TimeoutException e) {
                    break;
                }
            }
            if (!stopped) log.warn("ffprobe output reader did not stop within cleanup budget");
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxOutputBytes, 8192));
        byte[] buffer = new byte[Math.min(maxOutputBytes, 8192)];
        int count;
        while ((count = input.read(buffer, 0,
                (int) Math.min(buffer.length, (long) maxOutputBytes - output.size() + 1))) != -1) {
            if (count > maxOutputBytes - output.size()) {
                throw new OutputLimitException();
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static long remaining(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException();
        }
        return remaining;
    }

    private Optional<ProbeResult> parse(byte[] stdout) {
        try {
            FfprobeOutput out = json.readValue(stdout, FfprobeOutput.class);
            if (out == null || out.streams() == null || out.streams().stream().noneMatch(stream -> stream != null
                    && stream.index() != null && stream.index() >= 0) || out.format() == null
                    || out.format().formatName() == null || out.format().formatName().isBlank()) {
                return Optional.empty();
            }
            long maxPixels = 0;
            for (var stream : out.streams()) {
                // Cover art (MP3/M4A attached_pic) is not decoded by the detectors.
                if (stream == null || !"video".equals(stream.codecType()) || stream.isAttachedPicture()) continue;
                if (stream.width() != null && stream.height() != null) {
                    if (stream.width() <= 0 || stream.height() <= 0) return Optional.empty();
                    maxPixels = Math.max(maxPixels, (long) stream.width() * stream.height());
                }
            }
            return Optional.of(new ProbeResult(out.format().formatName(), parseDuration(out.format().duration()), maxPixels));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    private static Double parseDuration(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException e) {
            return null; // e.g. ffprobe emits "N/A" when the duration is unknown
        }
    }

    private static final class OutputLimitException extends IOException {}

    private record FfprobeOutput(
            @JsonProperty("streams") List<Stream> streams,
            @JsonProperty("format") Format format) {
        private record Stream(@JsonProperty("index") Integer index,
                @JsonProperty("codec_type") String codecType,
                @JsonProperty("width") Integer width, @JsonProperty("height") Integer height,
                @JsonProperty("disposition") Disposition disposition) {
            boolean isAttachedPicture() {
                return disposition != null && Integer.valueOf(1).equals(disposition.attachedPic());
            }
        }
        private record Disposition(@JsonProperty("attached_pic") Integer attachedPic) {}
        private record Format(
                @JsonProperty("format_name") String formatName,
                @JsonProperty("duration") String duration) {}
    }
}
