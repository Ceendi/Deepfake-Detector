package com.deepfake.fileservice.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class UploadConcurrencyFilter extends OncePerRequestFilter {
    private final Semaphore slots;

    public UploadConcurrencyFilter(@Value("${upload.max-concurrent:4}") int maximum,
            @Value("${spring.servlet.multipart.location:${java.io.tmpdir}/deepfake-uploads}") String location)
            throws IOException {
        if (maximum < 1) throw new IllegalArgumentException("Upload concurrency must be positive");
        slots = new Semaphore(maximum);
        Files.createDirectories(Path.of(location));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/api/files/upload".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Admission precedes multipart parsing, bounding both spooled and processed uploads.
        if (!slots.tryAcquire()) {
            response.setStatus(503);
            response.setHeader("Retry-After", "5");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            slots.release();
        }
    }
}
