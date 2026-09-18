package com.logyard4j.logyard.examples.kafkastreams;

import java.util.Locale;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OrderLoggingProcessor implements Processor<String, String, String, String> {
    private static final Logger LOG = LoggerFactory.getLogger(OrderLoggingProcessor.class);

    private ProcessorContext<String, String> context;

    @Override
    public void init(ProcessorContext<String, String> context) {
        this.context = context;
    }

    @Override
    public void process(Record<String, String> record) {
        LOG.info("Processing Kafka order key={}", record.key());
        String processed = record.value() == null ? null : record.value().toUpperCase(Locale.ROOT);
        context.forward(record.withValue(processed));
    }
}
