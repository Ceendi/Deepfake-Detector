package com.deepfake.orchestrator.security;

import com.deepfake.orchestrator.config.SecurityConfig;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JwtClientSecurityTest.Probe.class)
@Import({SecurityConfig.class, JwtRoleConverter.class, JwtClientSecurityTest.Probe.class})
class JwtClientSecurityTest {
    @org.springframework.web.bind.annotation.RestController
    static class Probe {
        @org.springframework.web.bind.annotation.GetMapping("/security-probe")
        String probe() { return "ok"; }
    }
    static final String ISSUER = "https://issuer.example/realms/deepfake";
    static final RSAKey KEY;
    static final HttpServer JWKS;
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            JWKS.start();
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }
    @AfterAll static void stop() { JWKS.stop(0); }
    @Autowired MockMvc mvc;

    private String token(String client, List<String> audience, String issuer, Instant expiry) {
        var claims = JwtClaimsSet.builder().issuer(issuer).subject("alice")
                .issuedAt(Instant.now().minusSeconds(600)).expiresAt(expiry).audience(audience);
        if (client != null) claims.claim("azp", client);
        var encoder = new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(new JWKSet(KEY)));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(KEY.getKeyID()).build(), claims.build())).getTokenValue();
    }

    private void check(String client, List<String> audience, int expected) throws Exception {
        mvc.perform(get("/security-probe").header("Authorization", "Bearer " +
                token(client, audience, ISSUER, Instant.now().plusSeconds(300))))
                .andExpect(status().is(expected));
    }

    @Test void allowedClientsPassAuthenticationAndReachRouting() throws Exception {
        check("deepfake-web", List.of("account"), 200);
        check("deepfake-cli", List.of("account"), 200);
        check("deepfake-loadtest", List.of("account"), 200);
        check(null, List.of("account", "deepfake-web"), 200);
    }

    @Test void foreignMissingOrEmptyClientsAreUnauthorized() throws Exception {
        check("other-client", List.of("account"), 401);
        check("other-client", List.of("deepfake-web"), 401);
        check("", List.of("deepfake-web"), 401);
        check(null, List.of("account"), 401);
        check(null, List.of(), 401);
    }

    @Test void issuerAndExpiryAreStillValidated() throws Exception {
        for (String value : List.of(token("deepfake-web", List.of(), "https://other.example", Instant.now().plusSeconds(300)),
                token("deepfake-web", List.of(), ISSUER, Instant.now().minusSeconds(300)))) {
            mvc.perform(get("/security-probe").header("Authorization", "Bearer " + value))
                    .andExpect(status().isUnauthorized());
        }
    }
}
