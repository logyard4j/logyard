package com.logyard4j.logyard.runtime.assembly.output;

import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.spi.output.EventSink;
import com.logyard4j.logyard.api.spi.output.OutputProvider;
import com.logyard4j.logyard.api.spi.output.OutputProviderContext;
import com.logyard4j.logyard.config.LogyardConfig;
import com.logyard4j.logyard.config.output.ConsoleOutputConfig;
import com.logyard4j.logyard.config.output.CustomOutputConfig;
import com.logyard4j.logyard.config.output.JsonFileOutputConfig;
import com.logyard4j.logyard.config.output.JsonStreamOutputConfig;
import com.logyard4j.logyard.config.output.OutputConfig;
import com.logyard4j.logyard.core.failure.ComponentInvocationBoundary;
import com.logyard4j.logyard.output.console.ConsoleSink;
import com.logyard4j.logyard.output.console.style.ConsoleTheme;
import com.logyard4j.logyard.output.console.terminal.ColorCapability;
import com.logyard4j.logyard.output.console.terminal.TerminalSupport;
import com.logyard4j.logyard.output.json.encoding.ResourceAttributes;
import com.logyard4j.logyard.output.json.file.JsonFileSink;
import com.logyard4j.logyard.output.json.file.rotation.RotationPolicy;
import com.logyard4j.logyard.output.json.stream.JsonLinesSink;
import com.logyard4j.logyard.runtime.assembly.output.DeliveryAssembler;
import com.logyard4j.logyard.runtime.assembly.output.EncoderResolver;
import com.logyard4j.logyard.runtime.assembly.output.FormatterResolver;
import com.logyard4j.logyard.runtime.extension.ExtensionRegistry;
import com.logyard4j.logyard.runtime.extension.ProviderResolver;

import java.io.PrintStream;
import java.time.ZoneId;
import java.util.Objects;

/** Factory for concrete output resources and their configured delivery decorators. */
public final class OutputFactory {
    private OutputFactory() {
    }

    static OutputPreparation prepare(LogyardConfig config, OutputConfig output, ExtensionRegistry extensions) {
        ResourceAttributes resource = EncoderResolver.resource(config);
        if (output instanceof JsonFileOutputConfig json) {
            JsonFileSink raw = jsonFile(config, json, resource, extensions);
            EventSink delivered = DeliveryAssembler.wrap(output, raw, config.deliveryFor(output), config.runtime().shutdownTimeout());
            return OutputPreparation.prepared(delivered, raw::activate);
        }
        EventSink raw = switch (output) {
            case ConsoleOutputConfig console -> console(config, console, extensions);
            case JsonStreamOutputConfig json -> jsonStream(config, json, resource, extensions);
            case CustomOutputConfig custom -> custom(config, custom, resource, extensions);
            case JsonFileOutputConfig ignored -> throw new IllegalStateException("JSON file preparation was not selected");
        };
        return OutputPreparation.active(
                DeliveryAssembler.wrap(output, raw, config.deliveryFor(output), config.runtime().shutdownTimeout()));
    }

    public static void validateDefinitions(LogyardConfig config, ExtensionRegistry extensions) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(extensions, "extensions");
        for (OutputConfig output : config.outputs().values()) {
            if (output instanceof ConsoleOutputConfig console) {
                FormatterResolver.consoleTheme(config, console.color().theme());
                TerminalSupport.colorCapability(console.color().capability());
            } else if (output instanceof CustomOutputConfig custom) {
                ProviderResolver.resolve(
                        extensions.outputs(),
                        custom.providerReference(),
                        "custom output '" + custom.name() + "'",
                        OutputProvider::configurationSpec);
            }
        }
    }

    private static EventSink console(LogyardConfig config, ConsoleOutputConfig output, ExtensionRegistry extensions) {
        ConsoleTheme theme = FormatterResolver.consoleTheme(config, output.color().theme());
        boolean colors = TerminalSupport.colorsEnabled(output.color().mode());
        ColorCapability capability = TerminalSupport.colorCapability(output.color().capability());
        PrintStream stream = "stdout".equals(output.stream()) ? System.out : System.err;
        return new ConsoleSink(
                stream,
                colors,
                theme,
                capability,
                ZoneId.systemDefault(),
                "compact".equals(output.exception().style()),
                "collapse".equals(output.exception().commonFrames()),
                false,
                FormatterResolver.resolve(config, output.formatter(), extensions));
    }

    private static EventSink jsonStream(
            LogyardConfig config,
            JsonStreamOutputConfig output,
            ResourceAttributes resource,
            ExtensionRegistry extensions) {
        PrintStream stream = "stdout".equals(output.stream()) ? System.out : System.err;
        return JsonLinesSink.bytes(
                new CheckedPrintStream(stream),
                EncoderResolver.resolve(config, output.encoder(), resource, extensions),
                output.flushInterval(),
                false);
    }

    private static JsonFileSink jsonFile(
            LogyardConfig config,
            JsonFileOutputConfig output,
            ResourceAttributes resource,
            ExtensionRegistry extensions) {
        RotationPolicy rotation = output.rotation() == null
                ? null
                : new RotationPolicy(
                        output.rotation().sizeBytes(),
                        output.rotation().keep(),
                        RotationPolicy.Compression.parse(output.rotation().compress()),
                        config.runtime().shutdownTimeout(),
                        output.rotation().interval());
        return JsonFileSink.prepare(
                output.path(),
                EncoderResolver.resolve(config, output.encoder(), resource, extensions),
                output.bufferBytes(),
                output.flushInterval(),
                output.append(),
                rotation,
                output.fsync());
    }

    private static EventSink custom(
            LogyardConfig config,
            CustomOutputConfig output,
            ResourceAttributes resource,
            ExtensionRegistry extensions) {
        OutputProvider provider = ProviderResolver.resolve(
                extensions.outputs(),
                output.providerReference(),
                "custom output '" + output.name() + "'",
                OutputProvider::configurationSpec);
        OutputProviderContext context = new OutputProviderContext(
                output.name(),
                AttributeSet.builder().putAll(resource.values()).build(),
                config.runtime().shutdownTimeout(),
                FormatterResolver.resolve(config, output.formatter(), extensions),
                output.encoder() == null ? null : EncoderResolver.resolve(config, output.encoder(), resource, extensions));
        return ComponentInvocationBoundary.call(
                "custom output '" + output.name() + "' provider creation",
                () -> Objects.requireNonNull(
                        provider.create(context, output.providerReference().configuration()),
                        "custom output provider returned null: " + output.name()));
    }
}
