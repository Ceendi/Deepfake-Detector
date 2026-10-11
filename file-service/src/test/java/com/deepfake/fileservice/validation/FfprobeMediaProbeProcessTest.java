package com.deepfake.fileservice.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FfprobeMediaProbeProcessTest {
    private static final String VALID = "{\"streams\":[{\"index\":0}],\"format\":{\"format_name\":\"wáv\",\"duration\":\"0.1\"}}";
    private static final int LIMIT = VALID.getBytes(StandardCharsets.UTF_8).length;
    @TempDir Path tmp;

    private FfprobeMediaProbe probe(String mode, String output, Duration timeout) {
        return new FfprobeMediaProbe(LIMIT, List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Child.class.getName(), mode, tmp.resolve("child.pid").toString(), output), timeout);
    }

    @AfterEach
    void childAndReaderAreGone() throws IOException {
        Path pid = tmp.resolve("child.pid");
        if (Files.exists(pid)) {
            long id = Long.parseLong(Files.readString(pid));
            assertThat(ProcessHandle.of(id).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        }
        assertThat(Thread.getAllStackTraces().keySet())
                .noneMatch(t -> t.isAlive() && t.getName().startsWith("ffprobe-output-"));
    }

    @Test
    void acceptsExactlyTheUtf8ByteLimit() throws IOException {
        assertThat(probe("output", VALID, Duration.ofSeconds(3)).probe(tmp.resolve("media")))
                .get().extracting(MediaProbe.ProbeResult::formatName).isEqualTo("wáv");
    }

    @Test
    void rejectsOneByteOverAndStopsAStalledProducerImmediately() throws IOException {
        long start = System.nanoTime();
        assertThat(probe("stall", VALID + " ", Duration.ofSeconds(8)).probe(tmp.resolve("media"))).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void stopsAProducerThatKeepsWritingAfterOverflow() throws IOException {
        long start = System.nanoTime();
        assertThat(probe("flood", VALID, Duration.ofSeconds(8)).probe(tmp.resolve("media"))).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"stall", "eof"})
    void deadlineIncludesPartialOutputAndExitAfterEof(String mode) throws IOException {
        long start = System.nanoTime();
        assertThat(probe(mode, "{", Duration.ofSeconds(1)).probe(tmp.resolve("media"))).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(900), Duration.ofSeconds(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "null", "42", "[]", "{}", "{\"streams\":[],\"format\":{}}",
            "{\"streams\":{},\"format\":{}}", "{\"streams\":[{}],\"format\":null}",
            "{\"streams\":[{\"index\":0}],\"format\":{\"format_name\":\"wav\"}}{}",
            "{\"streams\":[null],\"format\":{\"format_name\":\"wav\"}}",
            "{\"streams\":[{}],\"format\":{\"format_name\":\"wav\"}}",
            "{\"streams\":[{\"index\":-1}],\"format\":{\"format_name\":\"wav\"}}",
            "{\"streams\":[{}],\"format\":{\"format_name\":\"\"}}"})
    void rejectsMalformedJsonAndInvalidShapes(String output) throws IOException {
        assertThat(probe("output", output, Duration.ofSeconds(3)).probe(tmp.resolve("media"))).isEmpty();
    }

    @Test
    void rejectsNonzeroExitWithValidOutput() throws IOException {
        assertThat(probe("fail", VALID, Duration.ofSeconds(3)).probe(tmp.resolve("media"))).isEmpty();
    }

    @Test
    void interruptionCleansUpAndPreservesTheInterruptFlag() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                assertThat(probe("stall", "{", Duration.ofSeconds(8)).probe(tmp.resolve("media"))).isEmpty();
                interrupted.set(Thread.currentThread().isInterrupted());
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        caller.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!Files.exists(tmp.resolve("child.pid")) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(tmp.resolve("child.pid")).exists();
        } finally {
            caller.interrupt();
            caller.join(3000);
        }
        assertThat(caller.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(interrupted).isTrue();
    }

    @Test
    void reportsProcessStartupIoFailure() {
        FfprobeMediaProbe missing = new FfprobeMediaProbe(LIMIT,
                List.of(tmp.resolve("missing-ffprobe").toString()), Duration.ofSeconds(1));
        assertThatThrownBy(() -> missing.probe(tmp.resolve("media"))).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsNonpositiveConfiguration() {
        assertThatThrownBy(() -> new FfprobeMediaProbe(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FfprobeMediaProbe(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            byte[] output = args[2].getBytes(StandardCharsets.UTF_8);
            System.out.write(output);
            System.out.flush();
            switch (args[0]) {
                case "fail" -> System.exit(1);
                case "flood" -> {
                    while (true) {
                        System.out.write(new byte[8192]);
                        System.out.flush();
                    }
                }
                case "eof" -> { System.out.close(); Thread.sleep(30_000); }
                case "stall" -> Thread.sleep(30_000);
                default -> { }
            }
        }
    }
}
