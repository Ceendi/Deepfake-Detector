package com.deepfake.orchestrator.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AuthenticatedUserTest {

    @Test
    void extractsSubjectEmailAndRealmRoles() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-a")
                .claim("email", "alice@example.com")
                .claim("realm_access", Map.of("roles", List.of("USER", "ADMIN")))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AuthenticatedUser user = AuthenticatedUser.from(jwt);

        assertThat(user.id()).isEqualTo("user-a");
        assertThat(user.email()).isEqualTo("alice@example.com");
        assertThat(user.roles()).containsExactlyInAnyOrder("USER", "ADMIN");
    }

    @Test
    void rolesEmptyWhenRealmAccessMissing() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-b")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AuthenticatedUser user = AuthenticatedUser.from(jwt);

        assertThat(user.id()).isEqualTo("user-b");
        assertThat(user.email()).isNull();
        assertThat(user.roles()).isEmpty();
    }
}
