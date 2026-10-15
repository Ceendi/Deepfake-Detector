package com.deepfake.fileservice.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.deepfake.fileservice.exception.MediaProbeUnavailableException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FfprobeMediaProbeFailureTest {
    @Test
    void stdoutIoFailureReportsTemporaryUnavailability() throws Exception {
        var process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("broken stdout"); }
        });
        when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        when(process.getErrorStream()).thenReturn(InputStream.nullInputStream());
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        try (var builders = mockConstruction(ProcessBuilder.class, (builder, context) -> {
            when(builder.redirectError(ProcessBuilder.Redirect.DISCARD)).thenReturn(builder);
            when(builder.start()).thenReturn(process);
        })) {
            var probe = new FfprobeMediaProbe(1024, List.of("fake-ffprobe"), Duration.ofMillis(100));
            assertThatThrownBy(() -> probe.probe(Path.of("media.wav")))
                    .isInstanceOf(MediaProbeUnavailableException.class).hasCauseInstanceOf(IOException.class);
        }
        verify(process).destroyForcibly();
    }

    @Test
    void cleanupUsesABoundedWaitEvenWhenTheKilledProcessDoesNotExit() throws Exception {
        var process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        when(process.getErrorStream()).thenReturn(InputStream.nullInputStream());
        when(process.waitFor()).thenThrow(new AssertionError("Unbounded process wait in cleanup"));
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenAnswer(call -> {
            long nanos = ((TimeUnit) call.getArgument(1)).toNanos(call.getArgument(0));
            assertThat(nanos).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(5).toNanos());
            return false;
        });
        try (var builders = mockConstruction(ProcessBuilder.class, (builder, context) -> {
            when(builder.redirectError(ProcessBuilder.Redirect.DISCARD)).thenReturn(builder);
            when(builder.start()).thenReturn(process);
        })) {
            var probe = new FfprobeMediaProbe(1024, List.of("fake-ffprobe"), Duration.ofMillis(100));
            assertThatThrownBy(() -> probe.probe(Path.of("media.wav")))
                    .isInstanceOf(MediaProbeUnavailableException.class);
        }
        verify(process, never()).waitFor();
        verify(process, times(2)).waitFor(anyLong(), any(TimeUnit.class));
    }

    @Test
    void uninterruptibleReaderCannotExtendTheCleanupBudget() throws Exception {
        var reading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new InputStream() {
            @Override public int read() {
                reading.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
                return -1;
            }
        });
        when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        when(process.getErrorStream()).thenReturn(InputStream.nullInputStream());
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        try (var caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(() -> {
                try (var builders = mockConstruction(ProcessBuilder.class, (builder, context) -> {
                    when(builder.redirectError(ProcessBuilder.Redirect.DISCARD)).thenReturn(builder);
                    when(builder.start()).thenReturn(process);
                })) {
                    assertThatThrownBy(() -> new FfprobeMediaProbe(1024, List.of("fake-ffprobe"), Duration.ofMillis(100))
                            .probe(Path.of("media.wav"))).isInstanceOf(MediaProbeUnavailableException.class);
                    return true;
                }
            });
            try {
                assertThat(reading.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(result.get(7, TimeUnit.SECONDS)).isTrue();
            } finally { release.countDown(); }
        }
    }
}
