package com.zsumz.logyard.benchmarks.encoding;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.BoundedMessageFormat;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.benchmarks.fixture.BenchmarkFixtures;
import com.zsumz.logyard.core.processing.RedactionProcessor;
import com.zsumz.logyard.output.console.rendering.TemplateTextFormatter;
import com.zsumz.logyard.output.json.encoding.JsonEncoder;
import com.zsumz.logyard.output.json.encoding.ResourceAttributes;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Console, JSON, rendering, and redaction allocation baselines. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class EncodingBenchmark {
    private final LogEvent plain = BenchmarkFixtures.event(AttributeSet.builder().put("order.id", 42L).build());
    private final LogEvent secret = BenchmarkFixtures.event(
            AttributeSet.builder().put("order.id", 42L).put("payment.token", "secret").build());
    private final LogEvent nestedWithoutSecret = BenchmarkFixtures.event(AttributeSet.builder()
            .put("request", Map.of(
                    "users", List.of(Map.of("name", "Ada", "roles", List.of("admin", "operator"))),
                    "metadata", Map.of("region", "us-central", "attempt", 3)))
            .build());
    private final JsonEncoder json = new JsonEncoder(ResourceAttributes.service("orders", "benchmark", "1"));
    private final TemplateTextFormatter console =
            new TemplateTextFormatter("{timestamp} {level} {logger} - {message} {fields}", ZoneOffset.UTC);
    private final RedactionProcessor redaction = new RedactionProcessor(List.of("*.token"));
    private final String recursiveChoicePattern = "{0,choice,0#" + "'{1}'".repeat(1_600) + "}";
    private final Object[] recursiveChoiceParameters = {0, "x".repeat(2_048)};
    private final String defaultNumberPattern = "{0}".repeat(490);
    private final Object[] defaultNumberParameters = {new BigDecimal(BigInteger.ONE, -2_048)};
    private final Object[] oneStringParameter = {"value"};
    private final Object[] twoStringParameters = {"left", "right"};
    private final Object[] numberParameter = {42};
    private final Object[] dateParameter = {new Date(0L)};
    private final Object[] choiceParameter = {1};

    @Benchmark
    public String jsonEncoding() {
        return json.encode(plain);
    }

    @Benchmark
    public String consoleEncoding() {
        return console.format(plain);
    }

    @Benchmark
    public String cachedMessageRendering() {
        return plain.renderedMessage();
    }

    @Benchmark
    public LogEvent redactionWithoutMatch() {
        return redaction.process(plain);
    }

    @Benchmark
    public LogEvent nestedRedactionWithoutMatch() {
        return redaction.process(nestedWithoutSecret);
    }

    @Benchmark
    public LogEvent redactionWithMatch() {
        return redaction.process(secret);
    }

    @Benchmark
    public BoundedMessageFormat.Result rejectedRecursiveChoiceExpansion() {
        return BoundedMessageFormat.messageFormat(recursiveChoicePattern, recursiveChoiceParameters);
    }

    @Benchmark
    public BoundedMessageFormat.Result rejectedDefaultNumberExpansion() {
        return BoundedMessageFormat.messageFormat(defaultNumberPattern, defaultNumberParameters);
    }

    @Benchmark
    public BoundedMessageFormat.Result literalMessage() {
        return BoundedMessageFormat.literal("hello");
    }

    @Benchmark
    public BoundedMessageFormat.Result messageFormatOneString() {
        return BoundedMessageFormat.messageFormat("hello {0}", oneStringParameter);
    }

    @Benchmark
    public BoundedMessageFormat.Result messageFormatTwoStrings() {
        return BoundedMessageFormat.messageFormat("{0} {1}", twoStringParameters);
    }

    @Benchmark
    public BoundedMessageFormat.Result messageFormatNumber() {
        return BoundedMessageFormat.messageFormat("count {0,number,integer}", numberParameter);
    }

    @Benchmark
    public BoundedMessageFormat.Result messageFormatDate() {
        return BoundedMessageFormat.messageFormat("at {0,time,full} on {0,date,full}", dateParameter);
    }

    @Benchmark
    public BoundedMessageFormat.Result messageFormatChoice() {
        return BoundedMessageFormat.messageFormat("{0,choice,0#none|1#one|1<many}", choiceParameter);
    }

    @Benchmark
    public BoundedMessageFormat.Result printfString() {
        return BoundedMessageFormat.printf("hello %s", oneStringParameter);
    }

    @Benchmark
    public BoundedMessageFormat.Result printfNumber() {
        return BoundedMessageFormat.printf("count %d", numberParameter);
    }

    @Benchmark
    public BoundedMessageFormat.Result printfDate() {
        return BoundedMessageFormat.printf("at %1$tZ %1$tc", dateParameter);
    }
}
