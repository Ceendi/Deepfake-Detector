package com.deepfake.orchestrator.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

// Servlet twin of the gateway test — the converter is a conscious duplicate, keep all in sync.
class JwtRoleConverterTest {

    private final JwtRoleConverter converter = new JwtRoleConverter();

    @Test
    void mapsRealmRolesToPrefixedAuthorities() {
        Jwt jwt = jwtWithRealmRoles(List.of("USER", "ADMIN"));

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token).isNotNull();
        assertThat(token.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void returnsNoAuthoritiesWhenRealmAccessMissing() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-a")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token).isNotNull();
        assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void returnsNoAuthoritiesWhenRolesNotACollection() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-a")
                .claim("realm_access", Map.of("roles", "USER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        assertThat(converter.convert(jwt).getAuthorities()).isEmpty();
    }

    private Jwt jwtWithRealmRoles(List<String> roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user-a")
                .claim("realm_access", Map.of("roles", roles))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
