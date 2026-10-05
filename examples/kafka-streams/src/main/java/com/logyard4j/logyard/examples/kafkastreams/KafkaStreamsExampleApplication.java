package com.logyard4j.logyard.examples.kafkastreams;

import java.time.Duration;
import java.util.Properties;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Produced;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KafkaStreamsExampleApplication {
    static final String INPUT_TOPIC = "orders";
    static final String OUTPUT_TOPIC = "processed-orders";

    private static final Logger LOG = LoggerFactory.getLogger(KafkaStreamsExampleApplication.class);

    private KafkaStreamsExampleApplication() {
    }

    public static void main(String[] arguments) {
        KafkaStreams streams = new KafkaStreams(topology(), configuration());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Stopping Kafka Streams order processor");
            streams.close(Duration.ofSeconds(10));
        }, "kafka-streams-shutdown"));

        LOG.info("Starting Kafka Streams order processor inputTopic={} outputTopic={}",
                INPUT_TOPIC, OUTPUT_TOPIC);
        streams.start();
    }

    static Topology topology() {
        StreamsBuilder builder = new StreamsBuilder();
        builder.stream(INPUT_TOPIC, Consumed.with(Serdes.String(), Serdes.String()))
                .process(OrderLoggingProcessor::new)
                .to(OUTPUT_TOPIC, Produced.with(Serdes.String(), Serdes.String()));
        return builder.build();
    }

    static Properties configuration() {
        Properties properties = new Properties();
        properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "logyard-order-processor");
        properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG,
                System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"));
        properties.put(StreamsConfig.PROCESSING_EXCEPTION_HANDLER_GLOBAL_ENABLED_CONFIG, true);
        return properties;
    }
}
