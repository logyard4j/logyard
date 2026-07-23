package com.zsumz.logyard.runtime.assembly;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.spi.output.EventSink;
import com.zsumz.logyard.api.spi.output.OutputProvider;
import com.zsumz.logyard.api.spi.output.OutputProviderContext;
import com.zsumz.logyard.config.output.ConsoleOutputConfig;
import com.zsumz.logyard.config.output.CustomOutputConfig;
import com.zsumz.logyard.config.output.JsonFileOutputConfig;
import com.zsumz.logyard.config.output.JsonStreamOutputConfig;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.output.OutputConfig;
import com.zsumz.logyard.output.console.ColorCapability;
import com.zsumz.logyard.output.console.ConsoleSink;
import com.zsumz.logyard.output.console.ConsoleTheme;
import com.zsumz.logyard.output.console.TerminalSupport;
import com.zsumz.logyard.output.json.encoding.ResourceAttributes;
import com.zsumz.logyard.output.json.file.JsonFileSink;
import com.zsumz.logyard.output.json.file.rotation.RotationPolicy;
import com.zsumz.logyard.output.json.stream.JsonLinesSink;
import com.zsumz.logyard.runtime.assembly.output.DeliveryAssembler;
import com.zsumz.logyard.runtime.assembly.output.EncoderResolver;
import com.zsumz.logyard.runtime.assembly.output.FormatterResolver;
import com.zsumz.logyard.runtime.extension.ExtensionRegistry;
import com.zsumz.logyard.runtime.extension.ProviderResolver;

import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Objects;

/** Factory for concrete output resources and their configured delivery decorators. */
final class OutputFactory {
    private OutputFactory() {
    }

    static EventSink create(LogyardConfig config, OutputConfig output, ExtensionRegistry extensions) {
        ResourceAttributes resource = EncoderResolver.resource(config);
        EventSink raw = switch (output) {
            case ConsoleOutputConfig console -> console(config, console, extensions);
            case JsonStreamOutputConfig json -> jsonStream(config, json, resource, extensions);
            case JsonFileOutputConfig json -> jsonFile(config, json, resource, extensions);
            case CustomOutputConfig custom -> custom(config, custom, resource, extensions);
        };
        return DeliveryAssembler.wrap(output, raw, config.deliveryFor(output), config.runtime().shutdownTimeout());
    }

    static void validateDefinitions(LogyardConfig config, ExtensionRegistry extensions) {
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
        return new JsonLinesSink(
                new OutputStreamWriter(stream, StandardCharsets.UTF_8),
                EncoderResolver.resolve(config, output.encoder(), resource, extensions),
                output.flushInterval(),
                false);
    }

    private static EventSink jsonFile(
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
                        config.runtime().shutdownTimeout());
        return new JsonFileSink(
                output.path(),
                EncoderResolver.resolve(config, output.encoder(), resource, extensions),
                output.bufferBytes(),
                output.flushInterval(),
                output.append(),
                rotation);
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
        return Objects.requireNonNull(
                provider.create(context, output.providerReference().configuration()),
                "custom output provider returned null: " + output.name());
    }
}
