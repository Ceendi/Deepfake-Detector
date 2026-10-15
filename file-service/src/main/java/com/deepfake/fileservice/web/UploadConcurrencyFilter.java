package com.deepfake.fileservice.web;

import com.deepfake.fileservice.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class UploadConcurrencyFilter extends OncePerRequestFilter {
    private final Semaphore slots;
    private final int maximumPerUser;
    private final ConcurrentHashMap<String, Integer> slotsByUser = new ConcurrentHashMap<>();
    private final JsonMapper json = JsonMapper.builder().build();

    public UploadConcurrencyFilter(int maximum, String location) throws IOException {
        this(maximum, 2, location);
    }

    @Autowired
    public UploadConcurrencyFilter(@Value("${upload.max-concurrent:4}") int maximum,
            @Value("${upload.max-concurrent-per-user:2}") int maximumPerUser,
            @Value("${spring.servlet.multipart.location:${java.io.tmpdir}/deepfake-uploads}") String location)
            throws IOException {
        if (maximum < 1 || maximumPerUser < 1) throw new IllegalArgumentException("Upload concurrency must be positive");
        slots = new Semaphore(maximum);
        this.maximumPerUser = maximumPerUser;
        Files.createDirectories(Path.of(location));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        boolean upload = "POST".equals(request.getMethod()) && "/api/files/upload".equals(request.getServletPath());
        // MVC parses multipart before routing, including requests that will ultimately return 404.
        return !upload && !StringUtils.startsWithIgnoreCase(request.getContentType(), "multipart/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()
                || !StringUtils.hasText(jwt.getToken().getSubject())) {
            chain.doFilter(request, response);
            return;
        }
        String user = jwt.getToken().getSubject();
        if (!acquireUser(user)) {
            reject(response, 429, "UPLOAD_USER_LIMIT", "Trwa już zbyt wiele Twoich uploadów. Spróbuj ponownie za chwilę.");
            return;
        }
        try {
            // Admission precedes multipart parsing, bounding both spooled and processed uploads.
            if (!slots.tryAcquire()) {
                reject(response, 503, "SERVICE_UNAVAILABLE", "Serwer jest chwilowo przeciążony. Spróbuj ponownie za chwilę.");
                return;
            }
            try {
                chain.doFilter(request, response);
            } finally {
                slots.release();
            }
        } finally {
            slotsByUser.computeIfPresent(user, (key, count) -> count == 1 ? null : count - 1);
        }
    }

    private boolean acquireUser(String user) {
        boolean[] acquired = {false};
        slotsByUser.compute(user, (key, count) -> {
            int active = count == null ? 0 : count;
            if (active >= maximumPerUser) return count;
            acquired[0] = true;
            return active + 1;
        });
        return acquired[0];
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setHeader("Retry-After", "5");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String correlationId = MDC.get("correlationId");
        json.writeValue(response.getWriter(), new ErrorResponse(code, message, null,
                correlationId == null ? UUID.randomUUID().toString() : correlationId, Instant.now()));
    }
}
