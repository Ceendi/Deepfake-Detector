package com.deepfake.eurekaserver;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistryCsrfTokenRepositoryTest {
    @Test
    void tokenIsProvisionedWithoutSessionStateAndCannotBeOverwrittenByRequests() {
        var repository = new RegistryCsrfTokenRepository("disposable-csrf-token");
        var request = new MockHttpServletRequest();
        var token = repository.generateToken(request);
        assertEquals("X-CSRF-TOKEN", token.getHeaderName());
        assertEquals("disposable-csrf-token", token.getToken());
        repository.saveToken(null, request, new MockHttpServletResponse());
        assertSame(token, repository.loadToken(request));
        assertNull(request.getSession(false));
    }

    @Test
    void emptyProvisionedTokenIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RegistryCsrfTokenRepository(" "));
    }
}
