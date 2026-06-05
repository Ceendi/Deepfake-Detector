package com.deepfake.fileservice.validation;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs {@code ffprobe} as a subprocess (argument list, no shell — no injection) to confirm a real
 * media container and extract format_name + duration. stdout is drained on a separate thread: a
 * hostile file could fill the pipe buffer and block ffprobe, deadlocking a read-after-waitFor.
 */
@Slf4j
@Component
public class FfprobeMediaProbe implements MediaProbe {

    private static final int TIMEOUT_SECONDS = 10;

    private final JsonMapper json = JsonMapper.builder().build();

    @Override
    public Optional<ProbeResult> probe(Path file) throws IOException {
        List<String> cmd = List.of("ffprobe", "-v", "quiet", "-print_format", "json",
                "-show_format", "-show_streams", file.toAbsolutePath().toString());
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<byte[]> stdout = reader.submit(() -> process.getInputStream().readAllBytes());
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("ffprobe timed out for {}", file.getFileName());
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            return parse(stdout.get(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return Optional.empty();
        } catch (ExecutionException | TimeoutException e) {
            log.warn("reading ffprobe output failed for {}: {}", file.getFileName(), e.getMessage());
            return Optional.empty();
        } finally {
            reader.shutdownNow();
        }
    }

    private Optional<ProbeResult> parse(byte[] stdout) {
        FfprobeOutput out = json.readValue(new String(stdout, StandardCharsets.UTF_8), FfprobeOutput.class);
        if (out.streams() == null || out.streams().isEmpty() || out.format() == null) {
            return Optional.empty();
        }
        Double duration = parseDuration(out.format().duration());
        return Optional.of(new ProbeResult(out.format().formatName(), duration));
    }

    private static Double parseDuration(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException e) {
            return null; // e.g. ffprobe emits "N/A" for streams without a known duration
        }
    }

    private record FfprobeOutput(
            @JsonProperty("streams") List<Map<String, Object>> streams,
            @JsonProperty("format") Format format) {
        private record Format(
                @JsonProperty("format_name") String formatName,
                @JsonProperty("duration") String duration) {}
    }
}
