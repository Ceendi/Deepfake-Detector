package com.deepfake.orchestrator.sse;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.io.*;
import java.net.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.*;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.deepfake.orchestrator.dto.sse.*;
import com.deepfake.orchestrator.exception.GlobalExceptionHandler;

/** Actual Tomcat sockets, with gated emitter writes to make stalled-writer interleavings deterministic. */
@SpringBootTest(classes=SseHttpIsolationTest.Harness.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
 properties={"app.sse.global-connections=8","app.sse.user-connections=5","app.sse.analysis-connections=4",
 "app.sse.heartbeat=30s","app.sse.slow-write=5s","app.sse.lifetime=2m"})
class SseHttpIsolationTest {
    @LocalServerPort int port;
    @Autowired TestRegistry streams;
    @Autowired Gate gate;
    @Autowired LateHeaderFilter late;
    final List<Peer> peers=new ArrayList<>();
    @AfterEach void cleanup() throws Exception {
        gate.release.countDown(); peers.forEach(Peer::close); streams.unblock();
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).until(() -> { streams.heartbeat(); return streams.admitted()==0; });
        gate.reset(); streams.shortTimeout=false; streams.timedOut=new CountDownLatch(1);
    }
    @Test void severalStoppedReadersKeepHealthyAndNewClientsAndProducerIndependent() throws Exception {
        UUID id=UUID.randomUUID();
        for(int i=0;i<3;i++) peers.add(open(id,"slow",false));
        var healthy=open(id,"healthy",true); peers.add(healthy);
        streams.blockWrites();
        streams.sendProgress(id,progress(id,1));
        streams.awaitBlocked(3);
        assertThat(streams.admitted()).isEqualTo(4);
        try (var producer=Executors.newSingleThreadExecutor()) {
            producer.submit(() -> {
                for(int i=0;i<10000;i++) { streams.sendProgress(id,progress(id,i)); streams.heartbeat(); streams.observe(); }
            }).get(10,TimeUnit.SECONDS);
        }
        assertThat(streams.admitted()).isEqualTo(4);
        var newcomer=open(UUID.randomUUID(),"new",true); peers.add(newcomer);
        UUID next=newcomer.id;
        streams.sendResult(next,result(next));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(newcomer.lines).anyMatch(line -> line.contains("event:result")));
        streams.sendResult(id,result(id));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(healthy.lines).anyMatch(line -> line.contains("event:result")));
        assertThat(streams.admitted()).isGreaterThanOrEqualTo(3);
        peers.subList(0,3).forEach(Peer::close); streams.unblock();
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(streams.admitted()).isZero());
        peers.add(open(id,"slow",true)); // Actual peer close plus writer exit frees all three reservations.
    }
    @Test void servletTimeoutDuringBlockedSendDoesNotRecycleWriterCapacity() throws Exception {
        UUID id=UUID.randomUUID();streams.shortTimeout=true;
        var peer=open(id,"alice",false);peers.add(peer);
        streams.blockWrites();
        streams.sendProgress(id,progress(id,1));
        streams.awaitBlocked(1);
        assertThat(streams.timedOut.await(10,TimeUnit.SECONDS)).isTrue();
        assertThat(streams.admitted()).isEqualTo(1);assertThat(streams.writing()).isEqualTo(1);
        peer.close(); streams.unblock();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(streams.admitted()).isZero());
    }
    @Test void httpQuotasReturn429WithRetryAfterAndReleaseAfterRealCompletion() throws Exception {
        UUID id=UUID.randomUUID();
        for(int i=0;i<4;i++) peers.add(open(id,"alice",true));
        try(var denied=raw(id,"bob",false,"/test/")) {
            assertThat(denied.headers()).contains("429").containsIgnoringCase("Retry-After: 5");
        }
        peers.add(open(UUID.randomUUID(),"alice",true));
        try(var denied=raw(UUID.randomUUID(),"alice",false,"/test/")) { assertThat(denied.headers()).contains("429"); }
        for(int i=0;i<3;i++) peers.add(open(UUID.randomUUID(),"other"+i,true));
        try(var denied=raw(UUID.randomUUID(),"last",false,"/test/")) { assertThat(denied.headers()).contains("429"); }
        peers.getFirst().close();
        // A successful write can race the peer RST; probe until actual physical completion.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            streams.heartbeat();
            assertThat(streams.admitted()).isEqualTo(7);
        });
        peers.add(open(id,"bob",true));
    }
    @Test void actualMvcPreInitializationReplayUsesOnlyFixedMailboxAndFailureCannotArmOrphan() throws Exception {
        UUID id=UUID.randomUUID(); gate.block=true;
        try(var pool=Executors.newSingleThreadExecutor()) {
            var connection=pool.submit(() -> raw(id,"alice",true,"/preinit/"));
            assertThat(gate.entered.await(3,TimeUnit.SECONDS)).isTrue();
            var session=streams.session(gate.emitter);
            synchronized(session) { assertThat(session.writerStarted).isFalse(); assertThat(session.result).isEqualTo(result(id)); assertThat(session.video).isNull(); }
            var early=org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
            early.setAccessible(true); assertThat((Set<?>)early.get(gate.emitter)).isEmpty();
            gate.release.countDown(); var peer=connection.get(5,TimeUnit.SECONDS); peers.add(peer);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(peer.lines).anyMatch(line -> line.contains("event:result")));
        }
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(streams.admitted()).isZero());
        gate.reset(); gate.fail=true;
        try(var failed=raw(UUID.randomUUID(),"alice",false,"/preinit/")) { assertThat(failed.headers()).contains("500"); }
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(streams.admitted()).isZero());
        gate.reset();gate.failAfterStart=true;
        try(var failed=raw(UUID.randomUUID(),"alice",false,"/preinit/")) { assertThat(failed.headers()).contains("500"); }
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(streams.admitted()).isZero());
        assertThat(gate.failedSession.writerStarted).isFalse();
    }
    @Test void deliveryWaitsUntilFiltersStopTouchingResponseHeaders() throws Exception {
        // Spring Security's HeaderWriterFilter adds headers after the chain returns; a writer committing
        // concurrently corrupted Tomcat's header list (an empty-named header line reached the gateway).
        var peer=raw(UUID.randomUUID(),"alice",true,"/late/"); peers.add(peer);
        assertThat(peer.status).contains("200").contains("X-Late-Header: present").containsIgnoringCase("text/event-stream");
        assertThat(late.unwound.await(3,TimeUnit.SECONDS)).isTrue();
        assertThat(late.committedInsideChain).isFalse();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(peer.lines).contains(":hb"));
    }
    Peer open(UUID id,String owner,boolean drain) throws Exception { var peer=raw(id,owner,drain,"/test/"); assertThat(peer.status).contains("200"); return peer; }
    Peer raw(UUID id,String owner,boolean drain,String prefix) throws Exception {
        var peer=new Peer(id,port,prefix+id+"?owner="+owner); peer.status=peer.headers(); if(drain)peer.drain(); return peer;
    }
    static AnalysisProgressEvent progress(UUID id,int value) { return new AnalysisProgressEvent(id.toString(),"video",value%101,"x".repeat(512),"PROCESSING"); }
    static AnalysisResultEvent result(UUID id) { return new AnalysisResultEvent(id.toString(),"COMPLETED","FAKE",BigDecimal.ONE); }
    static class Peer implements AutoCloseable {
        final UUID id; final Socket socket=new Socket(); final BufferedReader reader;
        final Queue<String> lines=new ConcurrentLinkedQueue<>(); String status;
        Peer(UUID id,int port,String path)throws Exception { this(id,port,path,null); }
        Peer(UUID id,int port,String path,String token)throws Exception {
            this.id=id; socket.setReceiveBufferSize(1024); socket.setSoTimeout(5000); socket.connect(new InetSocketAddress("127.0.0.1",port));
            socket.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: localhost\r\n"+(token==null?"":"Authorization: Bearer "+token+"\r\n")+"Connection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            reader=new BufferedReader(new InputStreamReader(socket.getInputStream()));
        }
        String headers()throws Exception { if(status!=null)return status; var text=new StringBuilder(); String line; while((line=reader.readLine())!=null&&!line.isEmpty())text.append(line).append('\n'); return text.toString(); }
        void drain() { Thread.ofVirtual().start(() -> { try { String line; while((line=reader.readLine())!=null) { if(lines.size()>2000)lines.poll(); lines.add(line); }} catch(IOException ignored){} }); }
        public void close() { try { socket.setSoLinger(true,0);socket.close(); }catch(IOException ignored){} }
    }
    @Configuration(proxyBeanMethods=false)
    @org.springframework.boot.test.context.TestComponent
    @ImportAutoConfiguration({DispatcherServletAutoConfiguration.class,WebMvcAutoConfiguration.class,
        JacksonAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class})
    @Import({SseMvcLifecycle.class,GlobalExceptionHandler.class,Endpoint.class})
    static class Harness {
        @Bean TomcatServletWebServerFactory server() {
            var factory=new TomcatServletWebServerFactory(); factory.setPort(0); factory.addConnectorCustomizers(connector -> {
                connector.setProperty("socket.txBufSize","1024"); connector.setProperty("socket.appWriteBufSize","1024");
                connector.setProperty("connectionTimeout","120000");
            });return factory;
        }
        @Bean TestRegistry streams(SseLimits limits) { return new TestRegistry(limits); }
        @Bean Gate gate(TestRegistry streams) { return new Gate(streams); }
        @Bean LateHeaderFilter late() { return new LateHeaderFilter(); }
        @Bean FilterRegistrationBean<LateHeaderFilter> lateRegistration(LateHeaderFilter late) {
            var registration=new FilterRegistrationBean<>(late);
            registration.addUrlPatterns("/late/*"); return registration;
        }
    }
    /** Stands in for a header-writing filter that unwinds after the handler started async processing. */
    static class LateHeaderFilter extends OncePerRequestFilter {
        volatile boolean committedInsideChain; final CountDownLatch unwound=new CountDownLatch(1);
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
                throws ServletException,IOException {
            chain.doFilter(request,response);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            try { while(!response.isCommitted() && System.nanoTime()<deadline) Thread.sleep(10); }
            catch(InterruptedException error) { Thread.currentThread().interrupt(); }
            committedInsideChain=response.isCommitted();
            response.setHeader("X-Late-Header","present"); unwound.countDown();
        }
    }
    static class GatedEmitter extends SseEmitter {
        volatile boolean blocked;
        final CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        GatedEmitter(long timeout){super(timeout);}
        @Override public void send(SseEventBuilder event) throws IOException {
            if(blocked) {
                entered.countDown();
                try {
                    if(!release.await(30,TimeUnit.SECONDS))throw new IOException("write gate timed out");
                } catch(InterruptedException error) {Thread.currentThread().interrupt();throw new IOException(error);}
            }
            super.send(event);
        }
    }
    static class TestRegistry extends AnalysisStreamRegistry {
        volatile boolean shortTimeout;CountDownLatch timedOut=new CountDownLatch(1);
        final List<GatedEmitter> slow=new CopyOnWriteArrayList<>();
        final ThreadLocal<String> owner=new ThreadLocal<>();
        TestRegistry(SseLimits limits){super(limits);}
        @Override public SseEmitter register(UUID id,String user) {
            owner.set(user);
            try{return super.register(id,user);}finally{owner.remove();}
        }
        @Override protected SseEmitter createEmitter() {
            var emitter=new GatedEmitter(shortTimeout?5000L:120000L);
            if(shortTimeout)emitter.onTimeout(timedOut::countDown);
            if(shortTimeout || "slow".equals(owner.get()))slow.add(emitter);
            return emitter;
        }
        void blockWrites(){slow.forEach(emitter -> emitter.blocked=true);}
        void awaitBlocked(int expected)throws InterruptedException {
            assertThat(slow).hasSize(expected);
            for(var emitter:slow)assertThat(emitter.entered.await(10,TimeUnit.SECONDS)).isTrue();
        }
        void unblock(){slow.forEach(emitter -> emitter.release.countDown());slow.clear();}
    }
    static class Gate implements WebMvcConfigurer {
        final TestRegistry streams; AnalysisStreamRegistry.Session failedSession;
        Gate(TestRegistry streams){this.streams=streams;}
        volatile boolean block,fail,failAfterStart; volatile SseEmitter emitter;
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        void reset(){block=false;fail=false;failAfterStart=false;emitter=null;entered=new CountDownLatch(1);release=new CountDownLatch(1);}
        @Override public void configureAsyncSupport(AsyncSupportConfigurer config) {
            config.registerDeferredResultInterceptors(new DeferredResultProcessingInterceptor() {
                @Override public <T> void beforeConcurrentHandling(NativeWebRequest request,DeferredResult<T> result)throws Exception {
                    if(fail)throw new IOException("initialization fault");
                }
                @Override public <T> void preProcess(NativeWebRequest request,DeferredResult<T> result)throws Exception {
                    if(failAfterStart){failedSession=streams.session(emitter);throw new IOException("async-started initialization fault");}
                    if(block){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IOException("gate timed out");}
                }
            });
        }
    }
    @RestController @org.springframework.boot.test.context.TestComponent static class Endpoint {
        final AnalysisStreamRegistry streams; final Gate gate;
        Endpoint(AnalysisStreamRegistry streams,Gate gate){this.streams=streams;this.gate=gate;}
        @GetMapping(value={"/test/{id}","/preinit/{id}","/late/{id}"},produces="text/event-stream")
        SseEmitter stream(@PathVariable UUID id,@RequestParam String owner,HttpServletRequest request) {
            var emitter=streams.register(id,owner);
            if(request.getRequestURI().startsWith("/preinit")) {
                gate.emitter=emitter;for(int i=0;i<10000;i++)streams.sendProgress(id,progress(id,i));streams.sendResult(id,result(id));
            }
            return emitter;
        }
    }
}
