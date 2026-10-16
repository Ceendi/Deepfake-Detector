package com.deepfake.orchestrator.security;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.assertj.core.api.Assertions.*;

class JwtClientValidatorTest {
    @Test void customAllowlistIsTrimmedAndReplacesDefaults() {
        var validator = new JwtClientValidator(" custom-client, second-client ");
        assertThat(validator.validate(token("custom-client")).hasErrors()).isFalse();
        assertThat(validator.validate(token("second-client")).hasErrors()).isFalse();
        assertThat(validator.validate(token("deepfake-web")).hasErrors()).isTrue();
        assertThatThrownBy(() -> new JwtClientValidator(" , ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void absentClaimsAndMalformedAuthorizedPartyAreRejected() {
        var validator = new JwtClientValidator("deepfake-web");
        var missing = Jwt.withTokenValue("test").header("alg", "none").subject("alice").build();
        assertThat(validator.validate(missing).hasErrors()).isTrue();
        var malformed = Jwt.withTokenValue("test").header("alg", "none").subject("alice")
                .audience(List.of("deepfake-web")).claim("azp", List.of("deepfake-web")).build();
        assertThat(validator.validate(malformed).hasErrors()).isTrue();
    }

    private Jwt token(String client) {
        return Jwt.withTokenValue("test").header("alg", "none").subject("alice").claim("azp", client).build();
    }
}
