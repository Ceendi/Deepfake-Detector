package com.deepfake.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // SSE heartbeat now; stuck-job recovery in week 5
public class OrchestratorApplication {

	static void main(String[] args) {
		SpringApplication.run(OrchestratorApplication.class, args);
	}

}
