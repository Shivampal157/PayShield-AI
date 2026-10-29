package com.payshield.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    public static final String TRANSACTION_EVENTS_TOPIC = "transaction-events";
    public static final String TRANSACTION_EVENTS_DLT = "transaction-events.DLT";

    @Bean
    public NewTopic transactionEventsTopic() {
        return TopicBuilder.name(TRANSACTION_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic transactionEventsDltTopic() {
        return TopicBuilder.name(TRANSACTION_EVENTS_DLT)
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * Configures 3 total delivery attempts (1 initial + 2 retries) with exponential backoff
     * (500ms -> 1000ms) followed by DeadLetterPublishingRecoverer to transaction-events.DLT.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(2);
        backOff.setInitialInterval(500L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(2000L);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Kafka consumer retry attempt #{} for topic={} key={} reason={}",
                        deliveryAttempt, record.topic(), record.key(), ex.getMessage())
        );
        return errorHandler;
    }
}
