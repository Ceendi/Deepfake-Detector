package com.deepfake.fileservice.validation;

import com.fasterxml.jackson.annotation.JsonProperty;
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
        cmd.addAll(List.of("-v", "quiet", "-print_format", "json", "-show_entries",
                "format=format_name,duration:stream=index", file.toAbsolutePath().toString()));
        Process process = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        ExecutorService reader = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "ffprobe-output-" + process.pid()));
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
            return Optional.empty();
        } catch (TimeoutException e) {
            log.warn("ffprobe timed out for {}", file.getFileName());
            return Optional.empty();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof OutputLimitException) {
                log.warn("ffprobe output exceeded {} bytes for {}", maxOutputBytes, file.getFileName());
                return Optional.empty();
            }
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("Could not read ffprobe output", e.getCause());
        } finally {
            // Kill before closing pipes: close can otherwise wait for a blocked read to finish.
            process.destroyForcibly();
            boolean interrupted = Thread.interrupted();
            for (;;) {
                try {
                    process.waitFor();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            try (InputStream ignored = process.getInputStream()) {
                process.getOutputStream().close();
                process.getErrorStream().close();
            } finally {
                stdout.cancel(true);
                reader.shutdownNow();
                for (;;) {
                    try {
                        if (reader.awaitTermination(1, TimeUnit.SECONDS)) {
                            break;
                        }
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
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
            return Optional.of(new ProbeResult(out.format().formatName(), parseDuration(out.format().duration())));
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
        private record Stream(@JsonProperty("index") Integer index) {}
        private record Format(
                @JsonProperty("format_name") String formatName,
                @JsonProperty("duration") String duration) {}
    }
}
