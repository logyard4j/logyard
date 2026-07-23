package com.zsumz.logyard.runtime.assembly;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.api.diagnostics.EffectiveRoute;
import com.zsumz.logyard.api.spi.ContextProvider;
import com.zsumz.logyard.api.spi.TextFormatter;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.LoggerRuleConfig;
import com.zsumz.logyard.core.routing.RouteDefinition;
import com.zsumz.logyard.core.runtime.DefaultLogyardRuntime;
import com.zsumz.logyard.core.runtime.RuntimePlan;
import com.zsumz.logyard.output.console.ConsoleTheme;
import com.zsumz.logyard.runtime.assembly.output.EncoderResolver;
import com.zsumz.logyard.runtime.assembly.output.FormatterResolver;
import com.zsumz.logyard.runtime.assembly.processing.ProcessorAssembler;
import com.zsumz.logyard.runtime.assembly.routing.ConfiguredLoggerRuleResolver;
import com.zsumz.logyard.runtime.context.ContextProviderDiscovery;
import com.zsumz.logyard.runtime.extension.ExtensionRegistry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Compiles strict configuration into immutable, resource-owning runtime assemblies. */
public final class LogyardRuntimeFactory {
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
        AssembledOutputs outputs = null;
        try {
            outputs = OutputAssembler.assemble(config, current, extensions);
            ProcessorAssembler.Assembly processors = ProcessorAssembler.assemble(config, extensions, contextProviders);
            RouteDefinition root = route(config.rootLogger(), processors.contextEnabled(), processors.redactionEnabled());
            Map<String, RouteDefinition> loggers = new LinkedHashMap<>();
            for (String logger : config.loggers().keySet()) {
                LoggerRuleConfig rule = ConfiguredLoggerRuleResolver.resolve(logger, config.rootLogger(), config.loggers()).rule();
                loggers.put(logger, route(rule, processors.contextEnabled(), processors.redactionEnabled()));
            }
            RuntimePlan plan = new RuntimePlan(
                    root,
                    loggers,
                    outputs.sinks(),
                    processors.processors(),
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
        OutputFactory.validateDefinitions(config, extensions);
        FormatterResolver.validateDefinitions(config, extensions);
        EncoderResolver.validateDefinitions(config, extensions);
        ProcessorAssembler.validateDefinitions(config, extensions);
    }

    /** Resolves logger inheritance without constructing outputs or provider instances. */
    public static EffectiveRoute explain(LogyardConfig config, String loggerName) {
        validate(config);
        Objects.requireNonNull(loggerName, "loggerName");
        if (loggerName.isBlank()) {
            throw new IllegalArgumentException("logger name must not be blank");
        }
        ConfiguredLoggerRuleResolver.ResolvedRule effective = ConfiguredLoggerRuleResolver.resolve(loggerName, config.rootLogger(), config.loggers());
        List<String> processorNames =
                ProcessorAssembler.processorNames(effective.rule(), !contextProviders().isEmpty(), !config.context().redact().isEmpty());
        return new EffectiveRoute(
                loggerName,
                effective.rule().level(),
                effective.rule().outputs(),
                processorNames,
                effective.matchedRule());
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
                ProcessorAssembler.processorNames(rule, context, redact));
    }

    public static ConsoleTheme consoleTheme(LogyardConfig config, String name) {
        return FormatterResolver.consoleTheme(config, name);
    }

}
