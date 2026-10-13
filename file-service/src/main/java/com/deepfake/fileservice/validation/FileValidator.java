package com.deepfake.fileservice.validation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.springframework.stereotype.Component;

import com.deepfake.fileservice.exception.InvalidFileException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * Two-stage upload validation: magic bytes (Tika) then ffprobe. The detected MIME — not the client
 * content type or extension — decides acceptance. Returns the detected MIME + duration.
 */
@Component
public class FileValidator {

    // Every alias Tika may emit per format (e.g. WAV as audio/vnd.wave or audio/x-wav).
    static final Set<String> MIME_WHITELIST = Set.of(
            "video/mp4", "video/quicktime", "video/x-msvideo",
            "audio/vnd.wave", "audio/x-wav", "audio/wav",
            "audio/mpeg", "audio/mp3",
            "audio/x-flac", "audio/flac");

    // ffprobe format_name is a CSV (e.g. "mov,mp4,m4a"); accept if any token matches.
    static final Set<String> FFPROBE_FORMATS = Set.of("mp4", "mov", "m4a", "avi", "wav", "mp3", "flac");

    private final MediaProbe mediaProbe;
    private final double maxDurationSeconds;
    private final long maxPixels;
    private final Detector detector = new DefaultDetector();

    public FileValidator(MediaProbe mediaProbe) {
        this(mediaProbe, 1800);
    }

    public FileValidator(MediaProbe mediaProbe,
                         double maxDurationSeconds) {
        this(mediaProbe, maxDurationSeconds, 8294400);
    }

    @Autowired
    public FileValidator(MediaProbe mediaProbe,
                         @Value("${media.max-duration-seconds:1800}") double maxDurationSeconds,
                         @Value("${media.max-pixels:8294400}") long maxPixels) {
        if (!Double.isFinite(maxDurationSeconds) || maxDurationSeconds <= 0) {
            throw new IllegalArgumentException("Media duration limit must be finite and positive");
        }
        this.mediaProbe = mediaProbe;
        this.maxDurationSeconds = maxDurationSeconds;
        if (maxPixels <= 0) throw new IllegalArgumentException("Media pixel limit must be positive");
        this.maxPixels = maxPixels;
    }

    public Result validate(Path file) throws IOException {
        String mime = detectMime(file);
        if (!MIME_WHITELIST.contains(mime)) {
            throw new InvalidFileException("unsupported media type: " + mime);
        }
        MediaProbe.ProbeResult probe = mediaProbe.probe(file)
                .orElseThrow(() -> new InvalidFileException("not a valid media container"));
        if (!formatAllowed(probe.formatName())) {
            throw new InvalidFileException("unsupported container format: " + probe.formatName());
        }
        Double duration = probe.durationSeconds();
        if (probe.maxPixels() > maxPixels) {
            throw new InvalidFileException("[MEDIA_RESOLUTION_TOO_HIGH] Rozdzielczość nagrania jest za wysoka (maks. 4K).");
        }
        if (duration != null && (!Double.isFinite(duration) || duration < 0)) {
            throw new InvalidFileException("invalid media duration");
        }
        if (duration != null && duration > maxDurationSeconds) {
            throw new InvalidFileException("media duration exceeds " + maxDurationSeconds + " seconds");
        }
        return new Result(mime, probe.durationSeconds());
    }

    private String detectMime(Path file) throws IOException {
        // Empty Metadata: detect from bytes only, ignoring the client-provided name/extension.
        try (TikaInputStream stream = TikaInputStream.get(file)) {
            return detector.detect(stream, new Metadata()).toString();
        }
    }

    private static boolean formatAllowed(String formatName) {
        if (formatName == null) {
            return false;
        }
        for (String token : formatName.split(",")) {
            if (FFPROBE_FORMATS.contains(token.trim())) {
                return true;
            }
        }
        return false;
    }

    public record Result(String mimetype, Double durationSeconds) {}
}
