package com.deepfake.fileservice.security;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public final class JwtClientValidator implements OAuth2TokenValidator<Jwt> {
    private final Set<String> clients;

    public JwtClientValidator(String allowedClients) {
        clients = Arrays.stream(allowedClients.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (clients.isEmpty()) throw new IllegalArgumentException("JWT allowed clients must not be empty");
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        // Keycloak's azp identifies the requesting client; aud often contains only "account".
        // Never let an allowed audience override an explicitly foreign authorized party.
        Object authorizedParty = jwt.getClaims().get("azp");
        var audience = jwt.getAudience();
        boolean allowed = jwt.hasClaim("azp")
                ? authorizedParty instanceof String client && clients.contains(client)
                : audience != null && audience.stream().anyMatch(clients::contains);
        return allowed ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Token client is not allowed", null));
    }
}
