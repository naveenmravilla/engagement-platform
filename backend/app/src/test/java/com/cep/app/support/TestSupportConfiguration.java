package com.cep.app.support;

import com.cep.shared.api.messaging.OutboxSubscription;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

@TestConfiguration(proxyBeanMethods = false)
public class TestSupportConfiguration {
    public static final String QUEUE = "test.recording";
    public static final String SECOND_QUEUE = "test.second";
    public static final String TOPIC = "test.topic";

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }

    @Bean
    RecordingJobHandler recordingJobHandler(JdbcClient jdbc) {
        return new RecordingJobHandler(QUEUE, jdbc);
    }

    @Bean
    RecordingJobHandler secondJobHandler(JdbcClient jdbc) {
        return new RecordingJobHandler(SECOND_QUEUE, jdbc);
    }

    @Bean
    OutboxSubscription testTopicToRecording() {
        return new OutboxSubscription(TOPIC, QUEUE);
    }

    @Bean
    OutboxSubscription testTopicToSecond() {
        return new OutboxSubscription(TOPIC, SECOND_QUEUE);
    }
}
