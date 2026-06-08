package com.deepfake.orchestrator.security;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Immutable view of the authenticated principal, decoupling the controller layer from
 * Spring Security's {@link Jwt}. The service layer receives only {@code id()} (DIP).
 */
public record AuthenticatedUser(String id, String email, Set<String> roles) {

    public static AuthenticatedUser from(Jwt jwt) {
        return new AuthenticatedUser(
                jwt.getSubject(),
                jwt.getClaimAsString("email"),
                extractRealmRoles(jwt));
    }

    private static Set<String> extractRealmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return Set.of();
        }
        return roles.stream().map(Object::toString).collect(Collectors.toUnmodifiableSet());
    }
}
