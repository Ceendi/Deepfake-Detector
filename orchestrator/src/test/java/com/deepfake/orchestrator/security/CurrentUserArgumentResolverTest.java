package com.deepfake.orchestrator.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CurrentUserArgumentResolverTest {

    private final CurrentUserArgumentResolver resolver = new CurrentUserArgumentResolver();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void supportsOnlyAnnotatedAuthenticatedUserParameter() {
        assertThat(resolver.supportsParameter(param("annotated", AuthenticatedUser.class))).isTrue();
        assertThat(resolver.supportsParameter(param("notAnnotated", AuthenticatedUser.class))).isFalse();
        assertThat(resolver.supportsParameter(param("wrongType", String.class))).isFalse();
    }

    @Test
    void resolvesAuthenticatedUserFromJwtInSecurityContext() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-a")
                .claim("email", "alice@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

        Object resolved = resolver.resolveArgument(
                param("annotated", AuthenticatedUser.class), null, null, null);

        assertThat(resolved).isInstanceOf(AuthenticatedUser.class);
        assertThat(((AuthenticatedUser) resolved).id()).isEqualTo("user-a");
        assertThat(((AuthenticatedUser) resolved).email()).isEqualTo("alice@example.com");
    }

    @Test
    void throwsWhenAuthenticationIsNotJwtBased() {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("user-a", "n/a"));

        assertThatThrownBy(() -> resolver.resolveArgument(
                param("annotated", AuthenticatedUser.class), null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    private static MethodParameter param(String methodName, Class<?> type) {
        try {
            Method method = Probe.class.getDeclaredMethod(methodName, type);
            return new MethodParameter(method, 0);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @SuppressWarnings("unused")
    private static class Probe {
        void annotated(@CurrentUser AuthenticatedUser user) {}
        void notAnnotated(AuthenticatedUser user) {}
        void wrongType(@CurrentUser String user) {}
    }
}
