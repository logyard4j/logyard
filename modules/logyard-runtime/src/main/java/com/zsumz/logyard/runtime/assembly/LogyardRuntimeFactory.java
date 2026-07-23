package com.zsumz.logyard.runtime.assembly;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.diagnostics.EffectiveRoute;
import com.zsumz.logyard.api.spi.ContextProvider;
import com.zsumz.logyard.api.spi.EventProcessor;
import com.zsumz.logyard.api.spi.EventProcessorKind;
import com.zsumz.logyard.api.spi.EventProcessorProvider;
import com.zsumz.logyard.api.spi.TextFormatter;
import com.zsumz.logyard.config.EnricherConfig;
import com.zsumz.logyard.config.FilterConfig;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.LoggerRuleConfig;
import com.zsumz.logyard.config.ProviderFilterConfig;
import com.zsumz.logyard.config.RateLimitFilterConfig;
import com.zsumz.logyard.config.SamplingFilterConfig;
import com.zsumz.logyard.core.processing.ContextEnrichmentProcessor;
import com.zsumz.logyard.core.processing.RateLimitProcessor;
import com.zsumz.logyard.core.processing.RedactionProcessor;
import com.zsumz.logyard.core.processing.SamplingProcessor;
import com.zsumz.logyard.core.routing.RouteDefinition;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.core.runtime.RuntimePlan;
import com.zsumz.logyard.output.console.ConsoleTheme;
import com.zsumz.logyard.runtime.assembly.output.EncoderResolver;
import com.zsumz.logyard.runtime.assembly.output.FormatterResolver;
import com.zsumz.logyard.runtime.assembly.routing.ConfiguredLoggerRuleResolver;
import com.zsumz.logyard.runtime.context.ContextProviderDiscovery;
import com.zsumz.logyard.runtime.extension.ExtensionGuardrails;
import com.zsumz.logyard.runtime.extension.ExtensionRegistry;
import com.zsumz.logyard.runtime.extension.ProviderResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Compiles strict configuration into immutable, resource-owning runtime assemblies. */
public final class LogyardRuntimeFactory {
    private static final String CONTEXT_PROCESSOR = "logyard-context";
    private static final String REDACTION_PROCESSOR = "logyard-redaction";
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
        ExtensionRegistry extensions = ExtensionRegistry.discover();
        validate(config, extensions);
        List<ContextProvider> contextProviders = contextProviders();
        OutputAssembler.AssembledOutputs outputs = null;
        try {
            outputs = OutputAssembler.assemble(config, current, extensions);
            Map<String, EventProcessor> processors = processors(config, extensions, contextProviders);
            boolean context = processors.containsKey(CONTEXT_PROCESSOR);
            boolean redact = processors.containsKey(REDACTION_PROCESSOR);
            RouteDefinition root = route(config.rootLogger(), context, redact);
            Map<String, RouteDefinition> loggers = new LinkedHashMap<>();
            for (String logger : config.loggers().keySet()) {
                LoggerRuleConfig rule = ConfiguredLoggerRuleResolver.resolve(logger, config.rootLogger(), config.loggers()).rule();
                loggers.put(logger, route(rule, context, redact));
            }
            RuntimePlan plan = new RuntimePlan(
                    root,
                    loggers,
                    outputs.sinks(),
                    processors,
                    config.runtime().shutdownTimeout());
            return new RuntimeAssembly(config, plan, outputs.bindings());
        } catch (RuntimeException | Error failure) {
            if (outputs != null) {
                outputs.closeCreated(failure);
            }
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
        validate(Objects.requireNonNull(config, "config"), ExtensionRegistry.discover());
    }

    /** Resolves one named formatter for side-effect-free tooling such as render previews. */
    public static TextFormatter textFormatter(LogyardConfig config, String name) {
        Objects.requireNonNull(config, "config");
        return FormatterResolver.resolve(config, name, ExtensionRegistry.discover());
    }

    private static void validate(LogyardConfig config, ExtensionRegistry extensions) {
        OutputAssembler.validateDefinitions(config, extensions);
        FormatterResolver.validateDefinitions(config, extensions);
        EncoderResolver.validateDefinitions(config, extensions);
        for (EnricherConfig enricher : config.enrichers().values()) {
            ProviderResolver.resolveProcessor(
                    extensions.processors(),
                    enricher.providerReference(),
                    EventProcessorKind.ENRICHER,
                    "enricher '" + enricher.name() + "'");
        }
        for (FilterConfig filter : config.filters().values()) {
            if (filter instanceof ProviderFilterConfig custom) {
                ProviderResolver.resolveProcessor(
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
        ConfiguredLoggerRuleResolver.ResolvedRule effective = ConfiguredLoggerRuleResolver.resolve(loggerName, config.rootLogger(), config.loggers());
        List<String> processorNames = processorNames(effective.rule(), !contextProviders().isEmpty(), !config.context().redact().isEmpty());
        return new EffectiveRoute(
                loggerName,
                effective.rule().level(),
                effective.rule().outputs(),
                processorNames,
                effective.matchedRule());
    }

    private static Map<String, EventProcessor> processors(
            LogyardConfig config,
            ExtensionRegistry extensions,
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
                EventProcessorProvider provider = ProviderResolver.resolveProcessor(
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
            EventProcessorProvider provider = ProviderResolver.resolveProcessor(
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

    public static ConsoleTheme consoleTheme(LogyardConfig config, String name) {
        return FormatterResolver.consoleTheme(config, name);
    }

}
