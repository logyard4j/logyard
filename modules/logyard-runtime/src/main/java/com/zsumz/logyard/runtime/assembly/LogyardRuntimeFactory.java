package com.zsumz.logyard.runtime.assembly;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.diagnostics.EffectiveRoute;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.spi.ContextProvider;
import com.zsumz.logyard.api.spi.EventEncoder;
import com.zsumz.logyard.api.spi.EventEncoderProvider;
import com.zsumz.logyard.api.spi.EventProcessor;
import com.zsumz.logyard.api.spi.EventProcessorKind;
import com.zsumz.logyard.api.spi.EventProcessorProvider;
import com.zsumz.logyard.api.spi.EventSink;
import com.zsumz.logyard.api.spi.OutputProvider;
import com.zsumz.logyard.api.spi.OutputProviderContext;
import com.zsumz.logyard.api.spi.ProviderConfigurationSpec;
import com.zsumz.logyard.api.spi.TextFormatter;
import com.zsumz.logyard.api.spi.TextFormatterProvider;
import com.zsumz.logyard.config.ConsoleOutputConfig;
import com.zsumz.logyard.config.CustomOutputConfig;
import com.zsumz.logyard.config.DeliveryConfig;
import com.zsumz.logyard.config.EncoderConfig;
import com.zsumz.logyard.config.EnricherConfig;
import com.zsumz.logyard.config.FilterConfig;
import com.zsumz.logyard.config.FormatterConfig;
import com.zsumz.logyard.config.JsonEncoderConfig;
import com.zsumz.logyard.config.JsonFileOutputConfig;
import com.zsumz.logyard.config.JsonProfileConfig;
import com.zsumz.logyard.config.JsonStreamOutputConfig;
import com.zsumz.logyard.config.LoggerRuleConfig;
import com.zsumz.logyard.config.OutputConfig;
import com.zsumz.logyard.config.ProviderEncoderConfig;
import com.zsumz.logyard.config.ProviderFilterConfig;
import com.zsumz.logyard.config.ProviderFormatterConfig;
import com.zsumz.logyard.config.ProviderReferenceConfig;
import com.zsumz.logyard.config.RateLimitFilterConfig;
import com.zsumz.logyard.config.SamplingFilterConfig;
import com.zsumz.logyard.config.TemplateFormatterConfig;
import com.zsumz.logyard.config.TextStyleConfig;
import com.zsumz.logyard.config.ThemeConfig;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.core.delivery.AsyncSink;
import com.zsumz.logyard.core.delivery.FilteringSink;
import com.zsumz.logyard.core.delivery.OverflowPolicy;
import com.zsumz.logyard.core.processing.ContextEnrichmentProcessor;
import com.zsumz.logyard.core.processing.RateLimitProcessor;
import com.zsumz.logyard.core.processing.RedactionProcessor;
import com.zsumz.logyard.core.processing.SamplingProcessor;
import com.zsumz.logyard.core.routing.RouteDefinition;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.core.runtime.RuntimePlan;
import com.zsumz.logyard.output.console.AnsiStyle;
import com.zsumz.logyard.output.console.BuiltInThemes;
import com.zsumz.logyard.output.console.ColorCapability;
import com.zsumz.logyard.output.console.ConsoleSink;
import com.zsumz.logyard.output.console.ConsoleTheme;
import com.zsumz.logyard.output.console.TemplateTextFormatter;
import com.zsumz.logyard.output.console.TerminalSupport;
import com.zsumz.logyard.output.json.encoding.JsonAttributeTransform;
import com.zsumz.logyard.output.json.encoding.JsonEncoder;
import com.zsumz.logyard.output.json.encoding.JsonProfile;
import com.zsumz.logyard.output.json.encoding.ResourceAttributes;
import com.zsumz.logyard.output.json.file.JsonFileSink;
import com.zsumz.logyard.output.json.file.rotation.RotationPolicy;
import com.zsumz.logyard.output.json.stream.JsonLinesSink;
import com.zsumz.logyard.runtime.context.ContextProviderDiscovery;
import com.zsumz.logyard.runtime.encoding.EventEncoderProviderDiscovery;
import com.zsumz.logyard.runtime.extension.ExtensionGuardrails;
import com.zsumz.logyard.runtime.format.TextFormatterProviderDiscovery;
import com.zsumz.logyard.runtime.output.OutputProviderDiscovery;
import com.zsumz.logyard.runtime.processing.EventProcessorProviderDiscovery;

import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

/** Compiles strict configuration into immutable, resource-owning runtime assemblies. */
public final class LogyardRuntimeFactory {
    private static final String CONTEXT_PROCESSOR = "logyard-context";
    private static final String REDACTION_PROCESSOR = "logyard-redaction";
    private static final Set<String> BUILT_IN_THEME_NAMES = Set.of("ember", "nord", "mono");
    private static final Map<LogyardRuntime, RuntimeAssembly> ASSEMBLIES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private LogyardRuntimeFactory() {
    }

    public static LogyardRuntime create(LogyardConfig config) {
        RuntimeAssembly assembly = assemble(config, null);
        DefaultLogyardRuntime runtime = new DefaultLogyardRuntime(assembly.plan());
        attach(runtime, assembly);
        return runtime;
    }

    /** Builds a candidate plan, reusing only outputs with an identical immutable signature. */
    public static RuntimeAssembly assemble(LogyardConfig config, RuntimeAssembly current) {
        Objects.requireNonNull(config, "config");
        Extensions extensions = Extensions.discover();
        validate(config, extensions);
        List<ContextProvider> contextProviders = contextProviders();
        Map<String, EventSink> outputs = new LinkedHashMap<>();
        Map<String, OutputBinding> bindings = new LinkedHashMap<>();
        List<EventSink> created = new ArrayList<>();
        try {
            for (OutputConfig output : config.outputs().values()) {
                OutputSignature signature = signature(config, output);
                OutputBinding existing = current == null ? null : current.binding(output.name());
                EventSink sink;
                if (existing != null && existing.signature().equals(signature)) {
                    sink = existing.sink();
                } else {
                    Path exclusivePath = exclusivePath(output);
                    if (exclusivePath != null && current != null) {
                        OutputBinding locked = current.bindingForExclusivePath(exclusivePath);
                        if (locked != null) {
                            throw new IllegalArgumentException(
                                    "reload changes file output '" + output.name() + "' at locked path "
                                            + exclusivePath
                                            + "; restart the process for structural file changes");
                        }
                    }
                    sink = createOutput(config, output, extensions);
                    created.add(sink);
                }
                outputs.put(output.name(), sink);
                bindings.put(output.name(), new OutputBinding(sink, signature, exclusivePath(output)));
            }
            Map<String, EventProcessor> processors = processors(config, extensions, contextProviders);
            boolean context = processors.containsKey(CONTEXT_PROCESSOR);
            boolean redact = processors.containsKey(REDACTION_PROCESSOR);
            RouteDefinition root = route(config.rootLogger(), context, redact);
            Map<String, RouteDefinition> loggers = new LinkedHashMap<>();
            for (String logger : config.loggers().keySet()) {
                loggers.put(logger, route(effectiveRule(config, logger).rule(), context, redact));
            }
            RuntimePlan plan = new RuntimePlan(
                    root,
                    loggers,
                    outputs,
                    processors,
                    config.runtime().shutdownTimeout());
            return new RuntimeAssembly(config, plan, bindings);
        } catch (RuntimeException | Error failure) {
            closeCreated(created, failure);
            throw failure;
        }
    }

    /** Attaches the current assembly so adapters can observe live context policy changes. */
    public static void attach(LogyardRuntime runtime, RuntimeAssembly assembly) {
        ASSEMBLIES.put(
                Objects.requireNonNull(runtime, "runtime"),
                Objects.requireNonNull(assembly, "assembly"));
    }

    public static RuntimeAssembly assemblyFor(LogyardRuntime runtime) {
        return ASSEMBLIES.get(Objects.requireNonNull(runtime, "runtime"));
    }

    /** Returns the current immutable MDC/context allowlist for a runtime. */
    public static List<String> contextIncludeFor(LogyardRuntime runtime) {
        RuntimeAssembly assembly = assemblyFor(runtime);
        return assembly == null ? List.of() : assembly.contextInclude();
    }

    /** Validates every extension definition without opening files or starting output workers. */
    public static void validate(LogyardConfig config) {
        validate(Objects.requireNonNull(config, "config"), Extensions.discover());
    }

    /** Resolves one named formatter for side-effect-free tooling such as render previews. */
    public static TextFormatter textFormatter(LogyardConfig config, String name) {
        Objects.requireNonNull(config, "config");
        return formatter(config, name, Extensions.discover());
    }

    private static void validate(LogyardConfig config, Extensions extensions) {
        for (OutputConfig output : config.outputs().values()) {
            if (output instanceof ConsoleOutputConfig console) {
                consoleTheme(config, console.color().theme());
                TerminalSupport.colorCapability(console.color().capability());
            } else if (output instanceof CustomOutputConfig custom) {
                resolveProvider(
                        extensions.outputs(),
                        custom.providerReference(),
                        "custom output '" + custom.name() + "'",
                        OutputProvider::configurationSpec);
            }
        }
        for (JsonProfileConfig profile : config.jsonProfiles().values()) {
            jsonProfile(config, profile.name());
        }
        for (FormatterConfig formatter : config.formatters().values()) {
            if (formatter instanceof ProviderFormatterConfig custom) {
                resolveProvider(
                        extensions.formatters(),
                        custom.providerReference(),
                        "formatter '" + custom.name() + "'",
                        TextFormatterProvider::configurationSpec);
            }
        }
        for (EncoderConfig encoder : config.encoders().values()) {
            if (encoder instanceof ProviderEncoderConfig custom) {
                resolveProvider(
                        extensions.encoders(),
                        custom.providerReference(),
                        "encoder '" + custom.name() + "'",
                        EventEncoderProvider::configurationSpec);
            }
        }
        for (EnricherConfig enricher : config.enrichers().values()) {
            resolveProcessorProvider(
                    extensions.processors(),
                    enricher.providerReference(),
                    EventProcessorKind.ENRICHER,
                    "enricher '" + enricher.name() + "'");
        }
        for (FilterConfig filter : config.filters().values()) {
            if (filter instanceof ProviderFilterConfig custom) {
                resolveProcessorProvider(
                        extensions.processors(),
                        custom.providerReference(),
                        EventProcessorKind.FILTER,
                        "filter '" + custom.name() + "'");
            }
        }
    }

    /** Resolves logger inheritance without constructing outputs or provider instances. */
    public static EffectiveRoute explain(LogyardConfig config, String loggerName) {
        validate(config);
        Objects.requireNonNull(loggerName, "loggerName");
        if (loggerName.isBlank()) {
            throw new IllegalArgumentException("logger name must not be blank");
        }
        EffectiveRule effective = effectiveRule(config, loggerName);
        List<String> processorNames = processorNames(
                effective.rule(), !contextProviders().isEmpty(), !config.context().redact().isEmpty());
        return new EffectiveRoute(
                loggerName,
                effective.rule().level(),
                effective.rule().outputs(),
                processorNames,
                effective.matched());
    }

    private static EffectiveRule effectiveRule(LogyardConfig config, String loggerName) {
        LoggerRuleConfig effective = config.rootLogger();
        String matched = "root";
        List<Map.Entry<String, LoggerRuleConfig>> matches = config.loggers().entrySet().stream()
                .filter(entry -> loggerName.equals(entry.getKey())
                        || loggerName.startsWith(entry.getKey() + "."))
                .sorted(java.util.Comparator.comparingInt(entry -> entry.getKey().length()))
                .toList();
        for (Map.Entry<String, LoggerRuleConfig> match : matches) {
            LoggerRuleConfig child = match.getValue();
            effective = new LoggerRuleConfig(
                    child.level() == null ? effective.level() : child.level(),
                    child.outputs() == null ? effective.outputs() : child.outputs(),
                    child.enrich() == null ? effective.enrich() : child.enrich(),
                    child.filters() == null ? effective.filters() : child.filters());
            matched = match.getKey();
        }
        return new EffectiveRule(effective, matched);
    }

    private static OutputSignature signature(LogyardConfig config, OutputConfig output) {
        FormatterConfig formatter = null;
        EncoderConfig encoder = null;
        JsonProfileConfig profile = null;
        ThemeConfig theme = null;
        if (output instanceof ConsoleOutputConfig console) {
            formatter = console.formatter() == null
                    ? null
                    : config.formatters().get(console.formatter());
            theme = config.themes().get(console.color().theme());
        } else if (output instanceof JsonStreamOutputConfig stream) {
            encoder = encoderConfig(config, stream.encoder());
            profile = profileConfig(config, encoder);
        } else if (output instanceof JsonFileOutputConfig file) {
            encoder = encoderConfig(config, file.encoder());
            profile = profileConfig(config, encoder);
        } else if (output instanceof CustomOutputConfig custom) {
            formatter = custom.formatter() == null
                    ? null
                    : config.formatters().get(custom.formatter());
            encoder = encoderConfig(config, custom.encoder());
            profile = profileConfig(config, encoder);
        }
        boolean resourceAware = output instanceof JsonFileOutputConfig
                || output instanceof JsonStreamOutputConfig
                || output instanceof CustomOutputConfig;
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
        return encoder instanceof JsonEncoderConfig json
                ? config.jsonProfiles().get(json.profile())
                : null;
    }

    private static Path exclusivePath(OutputConfig output) {
        return output instanceof JsonFileOutputConfig json
                ? json.path().toAbsolutePath().normalize()
                : null;
    }

    private static EventSink createOutput(
            LogyardConfig config,
            OutputConfig output,
            Extensions extensions) {
        ResourceAttributes resource = resource(config);
        EventSink raw;
        if (output instanceof ConsoleOutputConfig console) {
            ConsoleTheme theme = consoleTheme(config, console.color().theme());
            boolean colors = TerminalSupport.colorsEnabled(console.color().mode());
            ColorCapability capability = TerminalSupport.colorCapability(console.color().capability());
            PrintStream stream = "stdout".equals(console.stream()) ? System.out : System.err;
            raw = new ConsoleSink(
                    stream,
                    colors,
                    theme,
                    capability,
                    ZoneId.systemDefault(),
                    "compact".equals(console.exception().style()),
                    "collapse".equals(console.exception().commonFrames()),
                    false,
                    formatter(config, console.formatter(), extensions));
        } else if (output instanceof JsonStreamOutputConfig json) {
            PrintStream stream = "stdout".equals(json.stream()) ? System.out : System.err;
            raw = new JsonLinesSink(
                    new OutputStreamWriter(stream, StandardCharsets.UTF_8),
                    encoder(config, json.encoder(), resource, extensions),
                    json.flushInterval(),
                    false);
        } else if (output instanceof JsonFileOutputConfig json) {
            RotationPolicy rotation = json.rotation() == null
                    ? null
                    : new RotationPolicy(
                            json.rotation().sizeBytes(),
                            json.rotation().keep(),
                            RotationPolicy.Compression.parse(json.rotation().compress()),
                            config.runtime().shutdownTimeout());
            raw = new JsonFileSink(
                    json.path(),
                    encoder(config, json.encoder(), resource, extensions),
                    json.bufferBytes(),
                    json.flushInterval(),
                    json.append(),
                    rotation);
        } else if (output instanceof CustomOutputConfig custom) {
            OutputProvider provider = resolveProvider(
                    extensions.outputs(),
                    custom.providerReference(),
                    "custom output '" + custom.name() + "'",
                    OutputProvider::configurationSpec);
            OutputProviderContext context = new OutputProviderContext(
                    custom.name(),
                    AttributeSet.builder().putAll(resource.values()).build(),
                    config.runtime().shutdownTimeout(),
                    formatter(config, custom.formatter(), extensions),
                    custom.encoder() == null
                            ? null
                            : encoder(config, custom.encoder(), resource, extensions));
            raw = Objects.requireNonNull(
                    provider.create(context, custom.providerReference().configuration()),
                    "custom output provider returned null: " + custom.name());
        } else {
            throw new IllegalArgumentException(
                    "unsupported Logyard output type: " + output.getClass().getName());
        }

        DeliveryConfig configuredDelivery = config.deliveryFor(output);
        EventSink delivery;
        if (output instanceof CustomOutputConfig) {
            delivery = new AsyncSink(
                    output.name(),
                    raw,
                    configuredDelivery.capacity(),
                    overflowPolicy(configuredDelivery),
                    config.runtime().shutdownTimeout(),
                    false);
        } else if (configuredDelivery.asynchronous()) {
            delivery = new AsyncSink(
                    output.name(),
                    raw,
                    configuredDelivery.capacity(),
                    overflowPolicy(configuredDelivery),
                    config.runtime().shutdownTimeout());
        } else {
            delivery = raw;
        }
        return new FilteringSink(output.minimumLevel(), delivery);
    }

    private static TextFormatter formatter(
            LogyardConfig config,
            String name,
            Extensions extensions) {
        if (name == null) {
            return null;
        }
        FormatterConfig configured = config.formatters().get(name);
        TextFormatter created;
        if (configured instanceof TemplateFormatterConfig template) {
            created = new TemplateTextFormatter(template.template(), ZoneId.systemDefault());
        } else if (configured instanceof ProviderFormatterConfig custom) {
            TextFormatterProvider provider = resolveProvider(
                    extensions.formatters(),
                    custom.providerReference(),
                    "formatter '" + name + "'",
                    TextFormatterProvider::configurationSpec);
            created = Objects.requireNonNull(
                    provider.create(custom.providerReference().configuration()),
                    "formatter provider returned null: " + name);
        } else {
            throw new IllegalArgumentException("unknown formatter definition '" + name + "'");
        }
        return ExtensionGuardrails.formatter(created);
    }

    private static EventEncoder encoder(
            LogyardConfig config,
            String name,
            ResourceAttributes resource,
            Extensions extensions) {
        EventEncoder created;
        if (name == null) {
            created = new JsonEncoder(resource, JsonProfile.named("logyard"));
        } else {
            EncoderConfig configured = config.encoders().get(name);
            if (configured instanceof JsonEncoderConfig json) {
                created = new JsonEncoder(resource, jsonProfile(config, json.profile()));
            } else if (configured instanceof ProviderEncoderConfig custom) {
                EventEncoderProvider provider = resolveProvider(
                        extensions.encoders(),
                        custom.providerReference(),
                        "encoder '" + name + "'",
                        EventEncoderProvider::configurationSpec);
                created = Objects.requireNonNull(
                        provider.create(custom.providerReference().configuration()),
                        "encoder provider returned null: " + name);
            } else {
                throw new IllegalArgumentException("unknown encoder definition '" + name + "'");
            }
        }
        return ExtensionGuardrails.encoder(created);
    }

    private static JsonProfile jsonProfile(LogyardConfig config, String name) {
        JsonProfileConfig configured = config.jsonProfiles().get(name);
        if (configured == null) {
            return JsonProfile.named(name);
        }
        com.zsumz.logyard.config.JsonAttributeTransformConfig attributes = configured.attributes();
        JsonAttributeTransform transform = new JsonAttributeTransform(
                JsonAttributeTransform.Mode.parse(attributes.mode()),
                attributes.prefix(),
                attributes.include(),
                attributes.exclude(),
                attributes.rename());
        return JsonProfile.custom(
                configured.name(),
                configured.preset(),
                configured.rename(),
                configured.drop(),
                transform);
    }

    private static ResourceAttributes resource(LogyardConfig config) {
        return ResourceAttributes.service(
                config.service().name(),
                config.service().namespace(),
                config.service().environment(),
                config.service().version(),
                config.service().instanceId(),
                config.resource().attributes());
    }

    private static Map<String, EventProcessor> processors(
            LogyardConfig config,
            Extensions extensions,
            List<ContextProvider> contextProviders) {
        Map<String, EventProcessor> result = new LinkedHashMap<>();
        LinkedHashSet<String> requiredFilters = new LinkedHashSet<>(safe(config.rootLogger().filters()));
        LinkedHashSet<String> requiredEnrichers = new LinkedHashSet<>(safe(config.rootLogger().enrich()));
        for (LoggerRuleConfig rule : config.loggers().values()) {
            requiredFilters.addAll(safe(rule.filters()));
            requiredEnrichers.addAll(safe(rule.enrich()));
        }
        for (String name : requiredFilters) {
            FilterConfig configured = config.filters().get(name);
            EventProcessor processor;
            if (configured instanceof SamplingFilterConfig sampling) {
                processor = new SamplingProcessor(
                        sampling.probability(), sampling.key(), sampling.seed());
            } else if (configured instanceof RateLimitFilterConfig rateLimit) {
                processor = new RateLimitProcessor(
                        rateLimit.permitsPerSecond(),
                        rateLimit.burst(),
                        rateLimit.key(),
                        rateLimit.maxKeys());
            } else if (configured instanceof ProviderFilterConfig custom) {
                EventProcessorProvider provider = resolveProcessorProvider(
                        extensions.processors(),
                        custom.providerReference(),
                        EventProcessorKind.FILTER,
                        "filter '" + name + "'");
                processor = Objects.requireNonNull(
                        provider.create(custom.providerReference().configuration()),
                        "filter provider returned null: " + name);
            } else {
                throw new IllegalArgumentException("unknown filter definition '" + name + "'");
            }
            result.put(name, processor);
        }
        for (String name : requiredEnrichers) {
            EnricherConfig configured = config.enrichers().get(name);
            EventProcessorProvider provider = resolveProcessorProvider(
                    extensions.processors(),
                    configured.providerReference(),
                    EventProcessorKind.ENRICHER,
                    "enricher '" + name + "'");
            EventProcessor processor = Objects.requireNonNull(
                    provider.create(configured.providerReference().configuration()),
                    "enricher provider returned null: " + name);
            result.put(name, ExtensionGuardrails.enricher(name, processor));
        }
        if (!contextProviders.isEmpty()) {
            result.put(
                    CONTEXT_PROCESSOR,
                    new ContextEnrichmentProcessor(
                            contextProviders, config.context().providerKeys()));
        }
        if (!config.context().redact().isEmpty()) {
            result.put(REDACTION_PROCESSOR, new RedactionProcessor(config.context().redact()));
        }
        return result;
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static List<ContextProvider> contextProviders() {
        return ContextProviderDiscovery.discover();
    }

    private static RouteDefinition route(
            LoggerRuleConfig rule,
            boolean context,
            boolean redact) {
        return RouteDefinition.root(
                Objects.requireNonNull(rule.level(), "effective logger level"),
                Objects.requireNonNull(rule.outputs(), "effective logger outputs"),
                processorNames(rule, context, redact));
    }

    private static List<String> processorNames(
            LoggerRuleConfig rule,
            boolean context,
            boolean redact) {
        List<String> result = new ArrayList<>();
        if (context) {
            result.add(CONTEXT_PROCESSOR);
        }
        result.addAll(safe(rule.filters()));
        result.addAll(safe(rule.enrich()));
        if (redact) {
            result.add(REDACTION_PROCESSOR);
        }
        return List.copyOf(result);
    }

    private static EventProcessorProvider resolveProcessorProvider(
            Map<String, EventProcessorProvider> providers,
            ProviderReferenceConfig reference,
            EventProcessorKind expectedKind,
            String label) {
        EventProcessorProvider provider = resolveProvider(
                providers, reference, label, EventProcessorProvider::configurationSpec);
        EventProcessorKind actual = Objects.requireNonNull(
                provider.kind(), label + " provider kind");
        if (actual != expectedKind) {
            throw new IllegalArgumentException(
                    label + " uses provider '" + reference.provider() + "' declared as "
                            + actual.name().toLowerCase(Locale.ROOT));
        }
        return provider;
    }

    private static <T> T resolveProvider(
            Map<String, T> providers,
            ProviderReferenceConfig reference,
            String label,
            Function<T, ProviderConfigurationSpec> configurationSpec) {
        T provider = providers.get(reference.provider());
        if (provider == null) {
            throw new IllegalArgumentException(
                    label + " references unavailable provider '" + reference.provider() + "'");
        }
        if (reference.implementation() != null
                && !reference.implementation().equals(provider.getClass().getName())) {
            throw new IllegalArgumentException(
                    label + " pins implementation '" + reference.implementation()
                            + "' but discovered '" + provider.getClass().getName() + "'");
        }
        try {
            Objects.requireNonNull(
                    configurationSpec.apply(provider), label + " provider configuration spec")
                    .validate(reference.configuration());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(label + ": " + exception.getMessage(), exception);
        }
        return provider;
    }

    private static OverflowPolicy overflowPolicy(DeliveryConfig delivery) {
        EnumMap<Level, OverflowPolicy.Rule> rules = new EnumMap<>(Level.class);
        delivery.overflow().forEach((level, configured) -> rules.put(
                level,
                new OverflowPolicy.Rule(configured.action(), configured.after())));
        return new OverflowPolicy(rules);
    }

    public static ConsoleTheme consoleTheme(LogyardConfig config, String name) {
        ThemeConfig custom = config.themes().get(name);
        if (custom == null) {
            if (!BUILT_IN_THEME_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "console output references unknown theme '" + name + "'");
            }
            return BuiltInThemes.named(name);
        }
        Map<String, AnsiStyle> roles = new LinkedHashMap<>();
        custom.roles().forEach((role, value) -> roles.put(role, style(value)));
        EnumMap<Level, AnsiStyle> levels = new EnumMap<>(Level.class);
        custom.levels().forEach((level, value) -> levels.put(level, style(value)));
        return new ConsoleTheme(name, roles, levels);
    }

    private static AnsiStyle style(TextStyleConfig value) {
        return new AnsiStyle(
                value.foreground(),
                value.background(),
                Boolean.TRUE.equals(value.bold()),
                Boolean.TRUE.equals(value.dim()),
                Boolean.TRUE.equals(value.italic()),
                Boolean.TRUE.equals(value.underline()));
    }

    private static void closeCreated(List<EventSink> sinks, Throwable primaryFailure) {
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

    private record EffectiveRule(LoggerRuleConfig rule, String matched) {
    }

    private record Extensions(
            Map<String, TextFormatterProvider> formatters,
            Map<String, EventEncoderProvider> encoders,
            Map<String, OutputProvider> outputs,
            Map<String, EventProcessorProvider> processors) {
        private static Extensions discover() {
            return new Extensions(
                    TextFormatterProviderDiscovery.discover(),
                    EventEncoderProviderDiscovery.discover(),
                    OutputProviderDiscovery.discover(),
                    EventProcessorProviderDiscovery.discover());
        }
    }
}
