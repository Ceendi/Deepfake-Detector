package com.deepfake.eurekaserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Standalone Eureka server (no external infra) — the context loads with no dependencies.
@SpringBootTest(properties = {"EUREKA_USER=registry-test", "EUREKA_PASSWORD=disposable-test-password"})
class EurekaServerApplicationTests {

	@Test
	void contextLoads() {
	}

}
