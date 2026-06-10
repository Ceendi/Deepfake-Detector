package com.deepfake.fileservice.validation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Probes a file as a real media container; abstracted so FileValidator can be unit-tested with a mock. */
public interface MediaProbe {

    /** Empty when the file is not a valid media container (probe failed, timed out, or no streams). */
    Optional<ProbeResult> probe(Path file) throws IOException;

    /** {@code formatName} is ffprobe's format_name (CSV, e.g. "mov,mp4,m4a"); duration may be null. */
    record ProbeResult(String formatName, Double durationSeconds) {}
}
