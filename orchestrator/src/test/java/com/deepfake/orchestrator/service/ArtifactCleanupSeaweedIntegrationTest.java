package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.images.builder.Transferable;
import com.deepfake.orchestrator.config.ObjectStorageConfig;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** The actual Compose-pinned SeaweedFS protocol, paging and idempotent deletion; disposable data only. */
@Testcontainers
class ArtifactCleanupSeaweedIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine");
    @Container static final GenericContainer<?> seaweed = new GenericContainer<>("chrislusf/seaweedfs:4.48")
        .withExposedPorts(8333)
        .withCopyToContainer(Transferable.of("""
            {"identities":[
              {"name":"test-admin","credentials":[{"accessKey":"admin","secretKey":"admin-secret"}],"actions":["Admin"]},
              {"name":"orchestrator","credentials":[{"accessKey":"orchestrator","secretKey":"orchestrator-secret"}],
               "actions":["Read:analysis-artifacts","Write:analysis-artifacts","List:analysis-artifacts"]}
            ]}
            """), "/etc/test-s3.json")
        .withCommand("server", "-dir=/tmp", "-s3", "-s3.port=8333", "-s3.config=/etc/test-s3.json", "-ip.bind=0.0.0.0", "-master.volumeSizeLimitMB=100", "-volume.max=8")
        .waitingFor(Wait.forHttp("/").forPort(8333).forStatusCodeMatching(code -> code == 200 || code == 403).withStartupTimeout(Duration.ofSeconds(120)));
    static AnnotationConfigApplicationContext context;
    static S3Client admin, s3;
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;

    @BeforeAll static void start() {
        String endpoint = "http://" + seaweed.getHost() + ":" + seaweed.getMappedPort(8333);
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
            "storage.endpoint", endpoint, "storage.region", "us-east-1", "storage.access-key", "orchestrator", "storage.secret-key", "orchestrator-secret")));
        context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
        context.register(ObjectStorageConfig.class); context.refresh(); s3 = context.getBean(S3Client.class);
        admin = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1).forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("admin", "admin-secret")))
            .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(5)).apiCallAttemptTimeout(Duration.ofSeconds(2))).build();
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions().untilAsserted(() -> {
            admin.createBucket(CreateBucketRequest.builder().bucket("analysis-artifacts").build());
        });
        admin.createBucket(CreateBucketRequest.builder().bucket("deepfake-uploads").build());
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).load().migrate();
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds); manager = new DataSourceTransactionManager(ds);
    }
    @AfterAll static void close() { if (admin != null) admin.close(); if (context != null) context.close(); }

    @Test void resumedPagesReachOldGarbageBehindActiveAndReferencedObjectsAndPreserveUnknownAndFreshKeys() throws Exception {
        UUID active = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID referencedId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID garbageId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        String activeKey = audio(active, 'a'), referenced = audio(referencedId, 'b'), garbage = audio(garbageId, 'c');
        String unknown = "zz-legacy/cam.png", fresh = audio(garbageId, 'd');
        insertAnalysis(active, "PROCESSING", "[]"); insertAnalysis(referencedId, "COMPLETED", "[\"" + referenced + "\"]");
        for (String key : List.of(activeKey, referenced, garbage, unknown)) put(key);
        Thread.sleep(31000); // Real last-modified timestamps are second-granularity; no synthetic ages.
        put(fresh);
        admin.putObject(PutObjectRequest.builder().bucket("deepfake-uploads").key("input.mp4").build(), RequestBody.fromString("source"));
        var first = worker(store()); first.scan();
        assertThat(jdbc.queryForObject("SELECT continuation_token FROM artifact_cleanup_scan", String.class)).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artifact_cleanup", Integer.class)).isZero();
        // Restart the worker/store between bounded pages. Old garbage lies AFTER the protected first page.
        var restarted = worker(store()); restarted.scan(); restarted.clean();
        assertThat(jdbc.queryForObject("SELECT continuation_token FROM artifact_cleanup_scan", String.class)).isNotBlank();
        var last = worker(store()); last.scan(); last.clean();
        assertThat(jdbc.queryForObject("SELECT continuation_token FROM artifact_cleanup_scan", String.class)).isNull();
        assertThat(list()).containsExactlyInAnyOrder(activeKey, referenced, unknown, fresh);
        assertThat(admin.getObjectAsBytes(GetObjectRequest.builder().bucket("deepfake-uploads").key("input.mp4").build()).asUtf8String()).isEqualTo("source");
        assertThatThrownBy(() -> s3.listObjectsV2(ListObjectsV2Request.builder().bucket("deepfake-uploads").build()))
            .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(403));
        // Duplicate/missing deletes must succeed with this pinned implementation too.
        var store = store(); new TransactionTemplate(manager).executeWithoutResult(tx -> store.enqueueDeletion(garbageId, List.of(garbage, garbage)));
        worker(store).clean(); assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artifact_cleanup", Integer.class)).isZero();
    }
    private static ArtifactCleanupStore store() { return new ArtifactCleanupStore(jdbc, manager, Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofSeconds(5)); }
    private static ArtifactCleanupWorker worker(ArtifactCleanupStore store) { return new ArtifactCleanupWorker(store, s3, "analysis-artifacts", 10, 2, Duration.ofSeconds(30)); }
    private static String audio(UUID id, char attempt) { return id + "/audio/gradcam_" + String.valueOf(attempt).repeat(32) + ".png"; }
    private static void put(String key) { admin.putObject(PutObjectRequest.builder().bucket("analysis-artifacts").key(key).build(), RequestBody.fromString("png")); }
    private static List<String> list() { return s3.listObjectsV2(ListObjectsV2Request.builder().bucket("analysis-artifacts").build()).contents().stream().map(S3Object::key).toList(); }
    private static void insertAnalysis(UUID id, String status, String keys) {
        jdbc.update("INSERT INTO analysis (id, user_id, file_id, file_key, type, status, audio_details) VALUES (?, 'alice', 'f', 'k', 'AUDIO', ?, jsonb_build_object('gradcamKeys', CAST(? AS jsonb)))", id, status, keys);
    }
}
