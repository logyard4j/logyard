package com.logyard4j.logyard.examples.kafkastreams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.logyard4j.logyard.api.Logyard;
import java.nio.file.Path;
import java.util.Properties;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class KafkaStreamsExampleApplicationTest {
    @TempDir
    Path stateDirectory;

    @Test
    void logsAndForwardsProcessedOrders() {
        Properties properties = KafkaStreamsExampleApplication.configuration();
        properties.put(StreamsConfig.STATE_DIR_CONFIG, stateDirectory.toString());

        try (TopologyTestDriver driver = new TopologyTestDriver(
                KafkaStreamsExampleApplication.topology(), properties)) {
            TestInputTopic<String, String> input = driver.createInputTopic(
                    KafkaStreamsExampleApplication.INPUT_TOPIC,
                    new StringSerializer(), new StringSerializer());
            TestOutputTopic<String, String> output = driver.createOutputTopic(
                    KafkaStreamsExampleApplication.OUTPUT_TOPIC,
                    new StringDeserializer(), new StringDeserializer());

            input.pipeInput("order-1042", "accepted");

            KeyValue<String, String> processed = output.readKeyValue();
            assertEquals("order-1042", processed.key);
            assertEquals("ACCEPTED", processed.value);
            assertTrue(output.isEmpty());
        } finally {
            Logyard.shutdown();
        }
    }
}
