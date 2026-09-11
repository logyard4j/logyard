package com.zsumz.logyard.compare;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.async.AsyncLoggerContext;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.jmx.RingBufferAdmin;

import java.util.concurrent.TimeUnit;
import java.util.Map;

/** Uses Disruptor-based AsyncLogger instances, with one asynchronous boundary. */
public final class Backend implements AutoCloseable {
    private final AsyncLoggerContext context;
    private final RingBufferAdmin queue;
    private final String policy;

    private Backend(AsyncLoggerContext context, String policy) {
        this.context = context;
        this.policy = policy;
        queue = context.createRingBufferAdmin();
    }

    public static Backend open(RunOptions options, MeasuredDestination destination) {
        System.setProperty("log4j2.contextSelector", "org.apache.logging.log4j.core.async.BasicAsyncLoggerContextSelector");
        if (options.policy().equals("matched-drop")) {
            System.setProperty("log4j2.asyncLoggerRingBufferSize", "4096");
            System.setProperty("log4j2.asyncQueueFullPolicy", DiscardAllPolicy.class.getName());
        }
        AsyncLoggerContext context = (AsyncLoggerContext) LogManager.getContext(false);
        var builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setStatusLevel(Level.ERROR);
        builder.add(builder.newRootLogger(Level.INFO));
        var configuration = builder.build();
        var output = new AbstractAppender("comparison-file", null, null, false, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                destination.accept(event.getMessage().getFormattedMessage(), event.getLevel().name(), event.getContextData()::getValue);
            }
        };
        output.start();
        configuration.addAppender(output);
        configuration.getRootLogger().addAppender(output, Level.ALL, null);
        context.start(configuration);
        return new Backend(context, options.policy());
    }

    public String name() {
        return "log4j2";
    }

    public String workerToken() {
        return "AsyncLogger";
    }

    public long capacity() {
        return queue.getBufferSize();
    }

    public long extraBatchCapacity() {
        return 0;
    }

    public boolean hasCapacity() {
        return queue.getRemainingCapacity() > 0;
    }

    public Map<String, Long> counters() {
        return policy.equals("matched-drop") ? Map.of("native_dropped", DiscardAllPolicy.dropped.sum()) : Map.of();
    }

    @Override
    public void close() {
        context.stop(5, TimeUnit.SECONDS);
    }
}
