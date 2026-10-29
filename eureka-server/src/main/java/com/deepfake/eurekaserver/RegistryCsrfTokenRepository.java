package com.deepfake.eurekaserver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.util.Assert;

final class RegistryCsrfTokenRepository implements CsrfTokenRepository {
    private final CsrfToken token;

    RegistryCsrfTokenRepository(String value) {
        Assert.hasText(value, "A registry CSRF token must be provisioned");
        token = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", value);
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return token;
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        return token;
    }

    @Override
    public void saveToken(CsrfToken value, HttpServletRequest request, HttpServletResponse response) {
        // The deployment provisions and rotates this machine-client secret out of band.
    }
}
