package com.deepfake.orchestrator.config;

import java.time.Duration;

import com.deepfake.orchestrator.listener.PoisonDeadLetterException;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;

@Configuration
public class RabbitConfig {
    public static final String RESULTS_LISTENER = "analysis-results";
    public static final String PROGRESS_LISTENER = "analysis-progress";
    public static final String DLQ_LISTENER = "analysis-dead-letters";
    public static final String Q_QUARANTINE = "analysis.backend.quarantine";
    public static final String EXCHANGE     = "analysis.exchange";
    public static final String DLX          = "analysis.dlx";
    public static final String Q_VIDEO      = "analysis.video";
    public static final String Q_AUDIO      = "analysis.audio";
    public static final String Q_RESULTS    = "analysis.results";
    public static final String Q_PROGRESS   = "analysis.progress";
    public static final String Q_VIDEO_DLQ  = "analysis.video.dlq";
    public static final String Q_AUDIO_DLQ  = "analysis.audio.dlq";
    public static final String Q_RESULTS_DLQ = "analysis.results.dlq";

    @Bean
    TopicExchange analysisExchange() { return new TopicExchange(EXCHANGE, true, false); }
    @Bean
    DirectExchange analysisDlx()     { return new DirectExchange(DLX, true, false); }

    @Bean
    Queue videoQueue() {
        return QueueBuilder.durable(Q_VIDEO)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", Q_VIDEO_DLQ)
                .build();
    }
    @Bean Queue audioQueue() {
        return QueueBuilder.durable(Q_AUDIO)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", Q_AUDIO_DLQ)
                .build();
    }
    @Bean Queue resultsQueue()  { return QueueBuilder.durable(Q_RESULTS).build(); }
    @Bean Queue progressQueue() { return QueueBuilder.durable(Q_PROGRESS).build(); }
    // Cancellation is NOT an AMQP event: a shared cancel queue would round-robin each cancel to
    // one of N detector consumers, and pika's BlockingConnection can't receive while processing.
    // Detectors instead poll the Redis flag cancel:{analysis_id} (see amqp-messages.md).
    @Bean Queue videoDlq()      { return QueueBuilder.durable(Q_VIDEO_DLQ).build(); }
    @Bean Queue audioDlq()      { return QueueBuilder.durable(Q_AUDIO_DLQ).build(); }
    // Fed by the recoverer, not broker-DLX: analysis.results keeps no DLX args so the detectors
    // that also declare it don't hit PRECONDITION_FAILED.
    @Bean Queue resultsDlq()    { return QueueBuilder.durable(Q_RESULTS_DLQ).build(); }

    // Backend-owned terminal parking queue; detector declarations remain unchanged.
    @Bean Queue backendQuarantine() { return QueueBuilder.durable(Q_QUARANTINE).build(); }
    @Bean Binding bQuarantine() { return BindingBuilder.bind(backendQuarantine()).to(analysisDlx()).with(Q_QUARANTINE); }

    @Bean Binding bVideo()    { return BindingBuilder.bind(videoQueue()).to(analysisExchange()).with("analysis.video"); }
    @Bean Binding bAudio()    { return BindingBuilder.bind(audioQueue()).to(analysisExchange()).with("analysis.audio"); }
    @Bean Binding bResults()  { return BindingBuilder.bind(resultsQueue()).to(analysisExchange()).with("analysis.results"); }
    @Bean Binding bProgress() { return BindingBuilder.bind(progressQueue()).to(analysisExchange()).with("analysis.progress"); }
    @Bean Binding bVideoDlq()   { return BindingBuilder.bind(videoDlq()).to(analysisDlx()).with(Q_VIDEO_DLQ); }
    @Bean Binding bAudioDlq()   { return BindingBuilder.bind(audioDlq()).to(analysisDlx()).with(Q_AUDIO_DLQ); }
    @Bean Binding bResultsDlq() { return BindingBuilder.bind(resultsDlq()).to(analysisDlx()).with(Q_RESULTS_DLQ); }

    @Bean
    MessageConverter jsonConverter() { return new JacksonJsonMessageConverter(); }

    @Bean
    AmqpRecoveryControl amqpRecovery(ObjectProvider<RabbitListenerEndpointRegistry> registry,
            @Value("${reliability.amqp.recovery-delay:5s}") Duration delay,
            @Value("${reliability.amqp.max-recovery-delay:60s}") Duration maxDelay) {
        return new AmqpRecoveryControl(registry, delay, maxDelay);
    }

    // The result factory retains Boot's configured finite retry policy and metric customizers.
    @Bean
    MessageRecoverer republishRecoverer(RabbitTemplate template, AmqpRecoveryControl recovery,
            @Value("${reliability.amqp.confirm-timeout:2s}") Duration timeout) {
        return new ConfirmedMessageRecoverer(template, Q_RESULTS_DLQ, timeout.toMillis(), recovery, RESULTS_LISTENER);
    }

    @Bean
    SimpleRabbitListenerContainerFactory resultListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        return listenerFactory(configurer, connectionFactory);
    }

    @Bean
    SimpleRabbitListenerContainerFactory progressListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = listenerFactory(configurer, connectionFactory);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .retryPolicy(RetryPolicy.builder().maxRetries(2).delay(Duration.ofMillis(100))
                        .multiplier(2).maxDelay(Duration.ofMillis(500)).build())
                .recoverer((message, cause) -> LoggerFactory.getLogger(RabbitConfig.class)
                        .warn("Advisory progress dropped after bounded retries: {}", cause.toString()))
                .build());
        return factory;
    }

    @Bean
    SimpleRabbitListenerContainerFactory deadLetterListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            RabbitTemplate template, AmqpRecoveryControl recovery,
            @Value("${reliability.amqp.confirm-timeout:2s}") Duration timeout) {
        SimpleRabbitListenerContainerFactory factory = listenerFactory(configurer, connectionFactory);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .retryPolicy(RetryPolicy.builder().maxRetries(2).delay(Duration.ofMillis(250))
                        .multiplier(2).maxDelay(Duration.ofSeconds(1))
                        .excludes(PoisonDeadLetterException.class).build())
                .recoverer(new ConfirmedMessageRecoverer(template, Q_QUARANTINE,
                        timeout.toMillis(), recovery, DLQ_LISTENER)).build());
        return factory;
    }

    private SimpleRabbitListenerContainerFactory listenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setForceStop(true);
        factory.setContainerCustomizer(container -> container.setShutdownTimeout(1000L));
        return factory;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory cf, MessageConverter c) {
        RabbitTemplate t = new RabbitTemplate(cf);
        t.setMessageConverter(c);
        t.setMandatory(true);
        // Own template bean opts out of Boot's auto-observation, so enable it by hand to inject the
        // W3C traceparent on publish. The listener side is enabled via application.yaml.
        t.setObservationEnabled(true);
        return t;
    }
}