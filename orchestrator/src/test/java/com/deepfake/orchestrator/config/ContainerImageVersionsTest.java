package com.deepfake.orchestrator.config;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ContainerImageVersionsTest {
    @Test void testcontainersUseComposeDependencyVersions() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String compose = Files.readString(root.resolve("docker-compose.yml"));
        String postgres = Files.readString(root.resolve("infra/postgres/Dockerfile"));
        var expected = new HashSet<String>();
        var pattern = Pattern.compile("(?:postgres|redis|rabbitmq|chrislusf/seaweedfs):[A-Za-z0-9.\\-]+");
        var versions = pattern.matcher(compose + postgres);
        while (versions.find()) expected.add(versions.group());
        for (String module : List.of("orchestrator", "file-service", "gateway")) {
            try (var paths = Files.walk(root.resolve(module + "/src/test"))) {
                for (Path source : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    var images = pattern.matcher(Files.readString(source));
                    while (images.find()) assertThat(expected).as(source.toString()).contains(images.group());
                }
            }
        }
    }
}
