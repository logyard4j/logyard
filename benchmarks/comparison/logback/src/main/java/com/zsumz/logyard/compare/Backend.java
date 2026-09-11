package com.zsumz.logyard.compare;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** Uses Logback's real AsyncAppender with an explicitly matched or default overflow policy. */
public final class Backend implements AutoCloseable {
    private final LoggerContext context;
    private final AsyncAppender async;

    private Backend(LoggerContext context, AsyncAppender async) {
        this.context = context;
        this.async = async;
    }

    public static Backend open(RunOptions options, MeasuredDestination destination) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        context.start();
        var output = new UnsynchronizedAppenderBase<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                destination.accept(event.getFormattedMessage(), event.getLevel().toString(), event.getMDCPropertyMap()::get);
            }
        };
        output.setContext(context);
        output.setName("comparison-file");
        output.start();
        AsyncAppender async = new AsyncAppender();
        async.setContext(context);
        async.setName("comparison");
        async.setMaxFlushTime(5_000);
        if (options.policy().equals("matched-drop")) {
            async.setQueueSize(4_096);
            async.setDiscardingThreshold(0);
            async.setNeverBlock(true);
        }
        async.addAppender(output);
        async.start();
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(async);
        return new Backend(context, async);
    }

    public String name() {
        return "logback";
    }

    public String workerToken() {
        return "AsyncAppender-Worker-comparison";
    }

    public long capacity() {
        return async.getQueueSize();
    }

    public long extraBatchCapacity() {
        return async.getQueueSize() + 1L;
    }

    public boolean hasCapacity() {
        return async.getRemainingCapacity() > 0;
    }

    public Map<String, Long> counters() {
        return Map.of();
    }

    @Override
    public void close() {
        context.stop();
    }
}
