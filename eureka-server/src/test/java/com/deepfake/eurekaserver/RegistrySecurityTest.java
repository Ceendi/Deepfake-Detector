package com.deepfake.eurekaserver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"EUREKA_USER=registry-test", "EUREKA_PASSWORD=disposable-test-password",
                "EUREKA_CSRF_TOKEN=disposable-csrf-token"})
class RegistrySecurityTest {
    @Value("${local.server.port}")
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private int request(String method, String path, String password, String body) throws Exception {
        return request(method, path, password, body, null);
    }

    private int request(String method, String path, String password, String body, String csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (password != null) {
            builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    ("registry-test:" + password).getBytes(StandardCharsets.UTF_8)));
        }
        if (csrf != null) {
            builder.header("X-CSRF-TOKEN", csrf);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void rejectsAnonymousAndIncorrectCredentialsForReadAndRegistration() throws Exception {
        assertEquals(401, request("GET", "/eureka/apps", null, ""));
        assertEquals(401, request("POST", "/eureka/apps/PROBE", null, "{}"));
        assertEquals(401, request("GET", "/eureka/apps", "incorrect", ""));
        assertEquals(401, request("POST", "/eureka/apps/PROBE", "incorrect", "{}"));
        assertEquals(401, request("POST", "/eureka/apps/PROBE", null, "{}", "disposable-csrf-token"));
        assertEquals(401, request("POST", "/eureka/apps/PROBE", "incorrect", "{}", "disposable-csrf-token"));
        assertEquals(401, request("GET", "/", null, ""));
    }

    @Test
    void authenticatedClientMustSupplyValidCsrfHeaderForRegistryMutations() throws Exception {
        String payload = """
                {"instance":{"instanceId":"security-probe","app":"PROBE",
                "hostName":"localhost","ipAddr":"127.0.0.1","status":"UP",
                "port":{"$":9999,"@enabled":"true"},
                "dataCenterInfo":{"@class":"com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo",
                "name":"MyOwn"}}}
                """;
        assertEquals(401, request("POST", "/eureka/apps/PROBE", "disposable-test-password", payload));
        assertEquals(401, request("POST", "/eureka/apps/PROBE", "disposable-test-password", payload, "wrong"));
        assertEquals(204, request("POST", "/eureka/apps/PROBE", "disposable-test-password", payload,
                "disposable-csrf-token"));
        assertEquals(200, request("GET", "/eureka/apps/PROBE/security-probe", "disposable-test-password", ""));
        assertEquals(200, request("PUT", "/eureka/apps/PROBE/security-probe", "disposable-test-password", "",
                "disposable-csrf-token"));
        assertEquals(200, request("DELETE", "/eureka/apps/PROBE/security-probe", "disposable-test-password", "",
                "disposable-csrf-token"));
    }

    @Test
    void healthIsPublicButCsrfRemainsEnabledOutsideRegistryApi() throws Exception {
        assertEquals(200, request("GET", "/actuator/health", null, ""));
        // CSRF rejects before Basic authentication establishes the security context.
        assertEquals(401, request("POST", "/", "disposable-test-password", "{}"));
        assertEquals(200, request("GET", "/", "disposable-test-password", ""));
    }
}
