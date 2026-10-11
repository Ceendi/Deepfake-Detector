# Bounded upload media probing

File-service validates uploads with Tika magic-byte detection followed by ffprobe. The probe requests only stream indexes and the container format name and duration; arbitrary upload tags are excluded. WAV, MP4, MOV, AVI, MP3 and FLAC remain accepted through the existing MIME/container checks. The 500 MB upload limit is unchanged.

`MEDIA_PROBE_MAX_OUTPUT_BYTES` configures `media.probe.max-output-bytes`, default **65536 bytes (64 KiB)**. It must be a positive integer. The reader retains at most this many bytes and reads one additional byte only to detect overflow, then terminates and reaps the child. JSON parsing happens after the bounded read. The existing ten-second deadline covers both stdout reading and process exit. All paths stop/reap the child, close its pipes and join its output reader; interruption preserves the caller's interrupt flag.

Overflow, timeout, nonzero exit and malformed or missing container/stream information reject the upload through the existing `422 INVALID_FILE` response. Process startup or pipe I/O errors propagate through the existing `500 INTERNAL_ERROR` response. The upload controller deletes its temporary file in either case.

Run `cd file-service && ./mvnw verify` with Java 25, Docker, ffmpeg and ffprobe available. Real-media tests fail if the binaries are missing. CI installs ffmpeg for the file-service job; the runtime image already includes it. Tests generate their media and a valid WAV with a 1 MiB control-byte LIST/INFO comment, demonstrating over 6 MiB of unrestricted JSON versus less than 1 KiB of selected output, without committing binary fixtures.
