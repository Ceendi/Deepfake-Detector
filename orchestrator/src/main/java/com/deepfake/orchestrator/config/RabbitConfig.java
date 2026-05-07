package com.deepfake.orchestrator.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    public static final String EXCHANGE     = "analysis.exchange";
    public static final String DLX          = "analysis.dlx";
    public static final String Q_VIDEO      = "analysis.video";
    public static final String Q_AUDIO      = "analysis.audio";
    public static final String Q_RESULTS    = "analysis.results";
    public static final String Q_PROGRESS   = "analysis.progress";
    public static final String Q_VIDEO_DLQ  = "analysis.video.dlq";
    public static final String Q_AUDIO_DLQ  = "analysis.audio.dlq";

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
    @Bean Queue videoDlq()      { return QueueBuilder.durable(Q_VIDEO_DLQ).build(); }
    @Bean Queue audioDlq()      { return QueueBuilder.durable(Q_AUDIO_DLQ).build(); }

    @Bean Binding bVideo()    { return BindingBuilder.bind(videoQueue()).to(analysisExchange()).with("analysis.video"); }
    @Bean Binding bAudio()    { return BindingBuilder.bind(audioQueue()).to(analysisExchange()).with("analysis.audio"); }
    @Bean Binding bResults()  { return BindingBuilder.bind(resultsQueue()).to(analysisExchange()).with("analysis.results"); }
    @Bean Binding bProgress() { return BindingBuilder.bind(progressQueue()).to(analysisExchange()).with("analysis.progress"); }
    @Bean Binding bVideoDlq() { return BindingBuilder.bind(videoDlq()).to(analysisDlx()).with(Q_VIDEO_DLQ); }
    @Bean Binding bAudioDlq() { return BindingBuilder.bind(audioDlq()).to(analysisDlx()).with(Q_AUDIO_DLQ); }

    @Bean
    MessageConverter jsonConverter() { return new JacksonJsonMessageConverter(); }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory cf, MessageConverter c) {
        RabbitTemplate t = new RabbitTemplate(cf);
        t.setMessageConverter(c);
        return t;
    }
}