package com.deepfake.orchestrator.sse;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.context.annotation.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import com.deepfake.orchestrator.OrchestratorApplication;
import com.deepfake.orchestrator.config.RabbitConfig;
import com.deepfake.orchestrator.entity.*;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.service.AnalysisService;
import com.zaxxer.hikari.HikariDataSource;

/** Full production HTTP/security/DB/AUTO-listener path with physically stalled subscribers. */
@SpringBootTest(classes=OrchestratorApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "eureka.client.enabled=false","spring.data.redis.password=","reliability.outbox.enabled=false",
 "reliability.artifact-cleanup.enabled=false","spring.datasource.hikari.maximum-pool-size=2",
 "spring.datasource.hikari.minimum-idle=1","app.sse.global-connections=8","app.sse.user-connections=5",
 "app.sse.analysis-connections=4","app.sse.slow-write=5s","app.sse.heartbeat=30s"})
@Import(SseBackendAcceptanceIntegrationTest.TransportConfig.class)
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class SseBackendAcceptanceIntegrationTest {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18.4-alpine");
    @Container static final GenericContainer<?> redis=new GenericContainer<>("redis:8.8.0-alpine").withExposedPorts(6379);
    @Container static final GenericContainer<?> broker=new GenericContainer<>("rabbitmq:4.3.1-alpine")
        .withEnv("RABBITMQ_DEFAULT_USER","test").withEnv("RABBITMQ_DEFAULT_PASS","test")
        .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*",1));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",postgres::getJdbcUrl);registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);registry.add("spring.data.redis.host",redis::getHost);
        registry.add("spring.data.redis.port",()->redis.getMappedPort(6379));registry.add("spring.rabbitmq.host",broker::getHost);
        registry.add("spring.rabbitmq.port",()->broker.getMappedPort(5672));registry.add("spring.rabbitmq.username",()->"test");
        registry.add("spring.rabbitmq.password",()->"test");
    }
    @LocalServerPort int port;
    @Autowired AnalysisStreamRegistry streams;
    @Autowired AnalysisRepository repository;
    @Autowired AnalysisService service;
    @Autowired RabbitTemplate rabbit;
    @Autowired DataSource datasource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.scheduling.TaskScheduler scheduler;
    @MockitoBean JwtDecoder decoder;
    final List<SseHttpIsolationTest.Peer> peers=new ArrayList<>();
    @BeforeEach void setup() {
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String user=call.getArgument(0);
            return Jwt.withTokenValue(user).header("alg","none").subject(user)
                .claim("realm_access",Map.of("roles",List.of(user.equals("no-role")?"OTHER":"USER"))).build();
        }); repository.deleteAll();
    }
    @AfterEach void cleanup() {
        peers.forEach(SseHttpIsolationTest.Peer::close);
        await().atMost(Duration.ofSeconds(10)).until(() -> {streams.heartbeat();return streams.admitted()==0;});
    }
    @Test void stoppedPeersDoNotHoldDbTransactionsOrBrokerAcknowledgementsAndTerminalReconciles() throws Exception {
        UUID id=seed();
        for(int i=0;i<3;i++)peers.add(open(id,"alice",false));
        var healthy=open(id,"alice",true);peers.add(healthy);
        long until=System.nanoTime()+Duration.ofSeconds(12).toNanos();int count=0;
        while(System.nanoTime()<until && streams.writingLongerThan(Duration.ofMillis(700))<3) {
            streams.sendProgress(id,SseHttpIsolationTest.progress(id,count++));LockSupport.parkNanos(100_000);
        }
        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(streams.writing()).isGreaterThanOrEqualTo(3));
        var scheduled=new java.util.concurrent.CountDownLatch(1);
        scheduler.schedule(scheduled::countDown,java.time.Instant.now());
        assertThat(scheduled.await(1,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        long started=System.nanoTime();
        rabbit.convertAndSend(RabbitConfig.EXCHANGE,RabbitConfig.Q_PROGRESS,Map.of("analysis_id",id.toString(),"source","video","progress",60,"stage","INFERENCE"));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PROCESSING));
        rabbit.convertAndSend(RabbitConfig.EXCHANGE,RabbitConfig.Q_RESULTS,result(id));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED));
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(5));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(healthy.lines).anyMatch(line -> line.contains("event:result")));
        assertThat(streams.admitted()).isGreaterThanOrEqualTo(3);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(((HikariDataSource)datasource).getHikariPoolMXBean().getActiveConnections()).isZero());
        String queues=broker.execInContainer("rabbitmqctl","list_queues","name","messages_ready","messages_unacknowledged").getStdout();
        assertThat(queues).contains("analysis.progress\t0\t0").contains("analysis.results\t0\t0");
        var response=get("/api/analysis/"+id,"alice");assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).contains("COMPLETED");
        UUID other=seed();var newcomer=open(other,"alice",true);peers.add(newcomer);
        rabbit.convertAndSend(RabbitConfig.EXCHANGE,RabbitConfig.Q_RESULTS,result(other));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(newcomer.lines).anyMatch(line -> line.contains("event:result")));
        var replay=open(id,"alice",true);peers.add(replay);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(replay.lines).anyMatch(line -> line.contains("event:result")));
    }
    @Test void ownershipAndRolesPrecedeAdmissionAndRollbackProducesNoPrematureTerminal() throws Exception {
        UUID id=seed();
        assertThat(get("/api/analysis/"+id+"/stream",null).statusCode()).isEqualTo(401);
        assertThat(get("/api/analysis/"+id+"/stream","no-role").statusCode()).isEqualTo(403);
        assertThat(get("/api/analysis/"+id+"/stream","bob").statusCode()).isEqualTo(404);
        assertThat(streams.admitted()).isZero();
        var healthy=open(id,"alice",true);peers.add(healthy);
        new TransactionTemplate(transactions).executeWithoutResult(tx->{service.handleResult(result(id));tx.setRollbackOnly();});
        await().during(Duration.ofMillis(250)).atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(healthy.lines).noneMatch(line -> line.contains("event:result")));
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PENDING);
        rabbit.convertAndSend(RabbitConfig.EXCHANGE,RabbitConfig.Q_RESULTS,result(id));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(healthy.lines).anyMatch(line -> line.contains("event:result")));
        UUID cancelled=seed();service.cancel(cancelled,"alice");
        var replay=open(cancelled,"alice",true);peers.add(replay);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(replay.lines).anyMatch(line -> line.contains("CANCELLED")));
    }
    UUID seed(){return repository.saveAndFlush(Analysis.builder().userId("alice").fileId("file").fileKey("key").type(AnalysisType.VIDEO).build()).getId();}
    Map<String,Object> result(UUID id){return Map.of("analysis_id",id.toString(),"source","video","status","COMPLETED","result",Map.of("prob_fake","0.8"));}
    SseHttpIsolationTest.Peer open(UUID id,String user,boolean drain)throws Exception {
        var peer=new SseHttpIsolationTest.Peer(id,port,"/api/analysis/"+id+"/stream",user);peer.status=peer.headers();
        assertThat(peer.status).contains("200");if(drain)peer.drain();return peer;
    }
    HttpResponse<String> get(String path,String token)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(3)).GET();
        if(token!=null)builder.header("Authorization","Bearer "+token);
        try(var client=HttpClient.newHttpClient()){return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());}
    }
    @TestConfiguration(proxyBeanMethods=false) static class TransportConfig {
        @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> smallBuffers() {
            return factory -> factory.addConnectorCustomizers(connector->{connector.setProperty("socket.txBufSize","1024");
                connector.setProperty("socket.appWriteBufSize","1024");connector.setProperty("connectionTimeout","120000");});
        }
    }
}
