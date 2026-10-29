package com.deepfake.eurekaserver;

import com.netflix.eureka.cluster.PeerEurekaNodes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Standalone Eureka server (no external infra) — the context loads with no dependencies.
@SpringBootTest(properties = {"EUREKA_USER=registry-test", "EUREKA_PASSWORD=disposable-test-password",
        "EUREKA_CSRF_TOKEN=disposable-csrf-token"})
class EurekaServerApplicationTests {

	@Autowired
	private PeerEurekaNodes peers;

	@Test
	void contextLoads() {
		assertTrue(peers.getPeerNodesView().isEmpty());
	}

}
