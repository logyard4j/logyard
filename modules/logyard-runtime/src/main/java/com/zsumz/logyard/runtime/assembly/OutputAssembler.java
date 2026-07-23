package com.zsumz.logyard.runtime.assembly;

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.api.spi.OutputProvider;
import com.zsumz.logyard.api.spi.OutputProviderContext;
import com.zsumz.logyard.config.ConsoleOutputConfig;
import com.zsumz.logyard.config.CustomOutputConfig;
import com.zsumz.logyard.config.EncoderConfig;
import com.zsumz.logyard.config.FormatterConfig;
import com.zsumz.logyard.config.JsonEncoderConfig;
import com.zsumz.logyard.config.JsonFileOutputConfig;
import com.zsumz.logyard.config.JsonProfileConfig;
import com.zsumz.logyard.config.JsonStreamOutputConfig;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.OutputConfig;
import com.zsumz.logyard.config.ThemeConfig;
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
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Assembles, reuses, and transactionally owns configured output resources. */
final class OutputAssembler {
    private OutputAssembler() {
    }

    static AssembledOutputs assemble(LogyardConfig config, RuntimeAssembly current, ExtensionRegistry extensions) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(extensions, "extensions");
        Map<String, EventSink> sinks = new LinkedHashMap<>();
        Map<String, OutputBinding> bindings = new LinkedHashMap<>();
        List<EventSink> created = new ArrayList<>();
        try {
            for (OutputConfig output : config.outputs().values()) {
                OutputSignature signature = signature(config, output);
                Path exclusivePath = exclusivePath(output);
                OutputBinding existing = current == null ? null : current.binding(output.name());
                EventSink sink;
                if (existing != null && existing.signature().equals(signature)) {
                    sink = existing.sink();
                } else {
                    verifyExclusivePathAvailable(current, output, exclusivePath);
                    sink = create(config, output, extensions);
                    created.add(sink);
                }
                sinks.put(output.name(), sink);
                bindings.put(output.name(), new OutputBinding(sink, signature, exclusivePath));
            }
            return new AssembledOutputs(sinks, bindings, created);
        } catch (RuntimeException | Error failure) {
            close(created, failure);
            throw failure;
        }
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

    private static EventSink create(LogyardConfig config, OutputConfig output, ExtensionRegistry extensions) {
        ResourceAttributes resource = EncoderResolver.resource(config);
        EventSink raw = switch (output) {
            case ConsoleOutputConfig console -> console(config, console, extensions);
            case JsonStreamOutputConfig json -> jsonStream(config, json, resource, extensions);
            case JsonFileOutputConfig json -> jsonFile(config, json, resource, extensions);
            case CustomOutputConfig custom -> custom(config, custom, resource, extensions);
        };
        return DeliveryAssembler.wrap(output, raw, config.deliveryFor(output), config.runtime().shutdownTimeout());
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

    private static OutputSignature signature(LogyardConfig config, OutputConfig output) {
        FormatterConfig formatter = null;
        EncoderConfig encoder = null;
        JsonProfileConfig profile = null;
        ThemeConfig theme = null;
        if (output instanceof ConsoleOutputConfig console) {
            formatter = console.formatter() == null ? null : config.formatters().get(console.formatter());
            theme = config.themes().get(console.color().theme());
        } else if (output instanceof JsonStreamOutputConfig stream) {
            encoder = encoderConfig(config, stream.encoder());
            profile = profileConfig(config, encoder);
        } else if (output instanceof JsonFileOutputConfig file) {
            encoder = encoderConfig(config, file.encoder());
            profile = profileConfig(config, encoder);
        } else if (output instanceof CustomOutputConfig custom) {
            formatter = custom.formatter() == null ? null : config.formatters().get(custom.formatter());
            encoder = encoderConfig(config, custom.encoder());
            profile = profileConfig(config, encoder);
        }

        boolean resourceAware =
                output instanceof JsonFileOutputConfig || output instanceof JsonStreamOutputConfig || output instanceof CustomOutputConfig;
        return new OutputSignature(
                output,
                config.deliveryFor(output),
                resourceAware ? config.service() : null,
                resourceAware ? config.resource() : null,
                theme,
                formatter,
                encoder,
                profile,
                config.runtime().shutdownTimeout());
    }

    private static EncoderConfig encoderConfig(LogyardConfig config, String name) {
        return name == null ? null : config.encoders().get(name);
    }

    private static JsonProfileConfig profileConfig(LogyardConfig config, EncoderConfig encoder) {
        return encoder instanceof JsonEncoderConfig json ? config.jsonProfiles().get(json.profile()) : null;
    }

    private static Path exclusivePath(OutputConfig output) {
        return output instanceof JsonFileOutputConfig json ? json.path().toAbsolutePath().normalize() : null;
    }

    private static void verifyExclusivePathAvailable(RuntimeAssembly current, OutputConfig output, Path exclusivePath) {
        if (exclusivePath == null || current == null) {
            return;
        }
        OutputBinding locked = current.bindingForExclusivePath(exclusivePath);
        if (locked != null) {
            throw new IllegalArgumentException(
                    "reload changes file output '" + output.name() + "' at locked path " + exclusivePath
                            + "; restart the process for structural file changes");
        }
    }

    private static void close(List<EventSink> sinks, Throwable primaryFailure) {
        Set<EventSink> closed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (EventSink sink : sinks) {
            if (!closed.add(sink)) {
                continue;
            }
            try {
                sink.close();
            } catch (RuntimeException closeFailure) {
                primaryFailure.addSuppressed(closeFailure);
            }
        }
    }

    record AssembledOutputs(Map<String, EventSink> sinks, Map<String, OutputBinding> bindings, List<EventSink> created) {
        AssembledOutputs {
            sinks = Collections.unmodifiableMap(new LinkedHashMap<>(sinks));
            bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
            created = List.copyOf(created);
        }

        void closeCreated(Throwable primaryFailure) {
            close(created, primaryFailure);
        }
    }
}
