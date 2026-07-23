package com.zsumz.logyard.runtime.assembly.processing;

import com.zsumz.logyard.api.spi.context.ContextProvider;
import com.zsumz.logyard.api.spi.processing.EventProcessor;
import com.zsumz.logyard.api.spi.processing.EventProcessorKind;
import com.zsumz.logyard.api.spi.processing.EventProcessorProvider;
import com.zsumz.logyard.config.processing.EnricherConfig;
import com.zsumz.logyard.config.processing.FilterConfig;
import com.zsumz.logyard.config.logging.LoggerRuleConfig;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.processing.ProviderFilterConfig;
import com.zsumz.logyard.config.processing.RateLimitFilterConfig;
import com.zsumz.logyard.config.processing.SamplingFilterConfig;
import com.zsumz.logyard.core.failure.ComponentInvocationBoundary;
import com.zsumz.logyard.core.processing.ContextEnrichmentProcessor;
import com.zsumz.logyard.core.processing.RateLimitProcessor;
import com.zsumz.logyard.core.processing.RedactionProcessor;
import com.zsumz.logyard.core.processing.SamplingProcessor;
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

/** Creates the exact processor set required by configured logger routes. */
public final class ProcessorAssembler {
    private static final String CONTEXT_PROCESSOR = "logyard-context";
    private static final String REDACTION_PROCESSOR = "logyard-redaction";

    private ProcessorAssembler() {
    }

    /**
     * Creates processors referenced by any route plus enabled context and redaction processors.
     *
     * @param config complete Logyard configuration
     * @param extensions discovered extension registry
     * @param contextProviders discovered context providers
     * @return immutable processor assembly and its built-in policy flags
     */
    public static Assembly assemble(LogyardConfig config, ExtensionRegistry extensions, List<ContextProvider> contextProviders) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(extensions, "extensions");
        Objects.requireNonNull(contextProviders, "contextProviders");

        Map<String, EventProcessor> processors = new LinkedHashMap<>();
        LinkedHashSet<String> requiredFilters = new LinkedHashSet<>(safe(config.rootLogger().filters()));
        LinkedHashSet<String> requiredEnrichers = new LinkedHashSet<>(safe(config.rootLogger().enrich()));
        for (LoggerRuleConfig rule : config.loggers().values()) {
            requiredFilters.addAll(safe(rule.filters()));
            requiredEnrichers.addAll(safe(rule.enrich()));
        }
        for (String name : requiredFilters) {
            processors.put(name, filter(config, extensions, name));
        }
        for (String name : requiredEnrichers) {
            processors.put(name, enricher(config, extensions, name));
        }

        boolean contextEnabled = !contextProviders.isEmpty();
        boolean redactionEnabled = !config.context().redact().isEmpty();
        if (contextEnabled) {
            processors.put(CONTEXT_PROCESSOR, new ContextEnrichmentProcessor(contextProviders, config.context().providerKeys()));
        }
        if (redactionEnabled) {
            processors.put(REDACTION_PROCESSOR, new RedactionProcessor(config.context().redact()));
        }
        return new Assembly(processors, contextEnabled, redactionEnabled);
    }

    /**
     * Returns processor names in the semantic execution order for one fully inherited logger rule.
     *
     * @param rule fully inherited logger rule
     * @param contextEnabled whether context capture is active
     * @param redactionEnabled whether redaction is active
     * @return immutable ordered processor names
     */
    public static List<String> processorNames(LoggerRuleConfig rule, boolean contextEnabled, boolean redactionEnabled) {
        Objects.requireNonNull(rule, "rule");
        List<String> result = new ArrayList<>();
        if (contextEnabled) {
            result.add(CONTEXT_PROCESSOR);
        }
        result.addAll(safe(rule.filters()));
        result.addAll(safe(rule.enrich()));
        if (redactionEnabled) {
            result.add(REDACTION_PROCESSOR);
        }
        return List.copyOf(result);
    }

    /** Validates provider-backed filter and enricher definitions without creating processor instances. */
    public static void validateDefinitions(LogyardConfig config, ExtensionRegistry extensions) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(extensions, "extensions");
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

    private static EventProcessor filter(LogyardConfig config, ExtensionRegistry extensions, String name) {
        FilterConfig configured = config.filters().get(name);
        if (configured instanceof SamplingFilterConfig sampling) {
            return new SamplingProcessor(sampling.probability(), sampling.key(), sampling.seed());
        }
        if (configured instanceof RateLimitFilterConfig rateLimit) {
            return new RateLimitProcessor(rateLimit.permitsPerSecond(), rateLimit.burst(), rateLimit.key(), rateLimit.maxKeys());
        }
        if (configured instanceof ProviderFilterConfig custom) {
            EventProcessorProvider provider = ProviderResolver.resolveProcessor(
                    extensions.processors(),
                    custom.providerReference(),
                    EventProcessorKind.FILTER,
                    "filter '" + name + "'");
            return ComponentInvocationBoundary.call(
                    "filter '" + name + "' provider creation",
                    () -> Objects.requireNonNull(
                            provider.create(custom.providerReference().configuration()),
                            "filter provider returned null: " + name));
        }
        throw new IllegalArgumentException("unknown filter definition '" + name + "'");
    }

    private static EventProcessor enricher(LogyardConfig config, ExtensionRegistry extensions, String name) {
        EnricherConfig configured = config.enrichers().get(name);
        EventProcessorProvider provider = ProviderResolver.resolveProcessor(
                extensions.processors(),
                configured.providerReference(),
                EventProcessorKind.ENRICHER,
                "enricher '" + name + "'");
        EventProcessor processor = ComponentInvocationBoundary.call(
                "enricher '" + name + "' provider creation",
                () -> Objects.requireNonNull(
                        provider.create(configured.providerReference().configuration()),
                        "enricher provider returned null: " + name));
        return ExtensionGuardrails.enricher(name, processor);
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }

    /**
     * Immutable processor graph plus route-policy flags derived during assembly.
     *
     * @param processors processors keyed by route name
     * @param contextEnabled whether context capture is active
     * @param redactionEnabled whether redaction is active
     */
    public record Assembly(Map<String, EventProcessor> processors, boolean contextEnabled, boolean redactionEnabled) {
        public Assembly {
            processors = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(processors, "processors")));
        }
    }
}
