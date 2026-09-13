package com.logyard4j.logyard.compare;

import ch.qos.logback.classic.LoggerContext;
import net.logstash.logback.composite.loggingevent.LogLevelJsonProvider;
import net.logstash.logback.composite.loggingevent.LoggingEventFormattedTimestampJsonProvider;
import net.logstash.logback.composite.loggingevent.LoggingEventJsonProviders;
import net.logstash.logback.composite.loggingevent.LoggingEventNestedJsonProvider;
import net.logstash.logback.composite.loggingevent.MdcJsonProvider;
import net.logstash.logback.composite.loggingevent.MessageJsonProvider;
import net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder;

/** Configures the shipped Logback JSON encoder to emit the matched comparison fields. */
final class NativeJson {
    private NativeJson() {
    }

    static LoggingEventCompositeJsonEncoder create(LoggerContext context, RunOptions options) {
        var timestamp = new LoggingEventFormattedTimestampJsonProvider();
        timestamp.setFieldName("timestamp");
        timestamp.setPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        timestamp.setTimeZone("UTC");
        var providers = new LoggingEventJsonProviders();
        providers.addTimestamp(timestamp);
        providers.addLogLevel(new LogLevelJsonProvider());
        providers.addMessage(new MessageJsonProvider());
        if (options.fields() > 0) {
            var fields = new LoggingEventJsonProviders();
            fields.addMdc(new MdcJsonProvider());
            var attributes = new LoggingEventNestedJsonProvider();
            attributes.setFieldName("attributes");
            attributes.setProviders(fields);
            providers.addNestedField(attributes);
        }
        var encoder = new LoggingEventCompositeJsonEncoder();
        encoder.setContext(context);
        encoder.setProviders(providers);
        encoder.setLineSeparator("UNIX");
        encoder.start();
        if (!encoder.isStarted()) throw new IllegalStateException("native JSON encoder did not start");
        return encoder;
    }
}
