package com.logyard4j.runtime.assembly.output;

import com.logyard4j.api.spi.encoding.EventEncoder;
import com.logyard4j.api.spi.encoding.EventEncoderProvider;
import com.logyard4j.config.encoding.EncoderConfig;
import com.logyard4j.config.encoding.JsonAttributeTransformConfig;
import com.logyard4j.config.encoding.JsonEncoderConfig;
import com.logyard4j.config.encoding.JsonProfileConfig;
import com.logyard4j.config.LogyardConfig;
import com.logyard4j.config.encoding.ProviderEncoderConfig;
import com.logyard4j.core.failure.ComponentInvocationBoundary;
import com.logyard4j.output.json.encoding.JsonAttributeTransform;
import com.logyard4j.output.json.encoding.JsonEncoder;
import com.logyard4j.output.json.encoding.JsonProfile;
import com.logyard4j.output.json.encoding.ResourceAttributes;
import com.logyard4j.runtime.extension.ExtensionGuardrails;
import com.logyard4j.runtime.extension.ExtensionRegistry;
import com.logyard4j.runtime.extension.ProviderResolver;

import java.util.Objects;

/** Resolves event encoders, JSON profiles, and resource attributes. */
public final class EncoderResolver {
    private EncoderResolver() {
    }

    /**
     * Resolves one configured encoder.
     *
     * @param config complete Logyard configuration
     * @param name encoder name, or {@code null} for Logyard's default JSON encoder
     * @param resource immutable service/resource attributes
     * @param extensions discovered extension registry
     * @return bounded built-in JSON encoder or guarded extension encoder
     */
    public static EventEncoder resolve(
            LogyardConfig config,
            String name,
            ResourceAttributes resource,
            ExtensionRegistry extensions) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(extensions, "extensions");

        EventEncoder created;
        if (name == null) {
            created = new JsonEncoder(resource, JsonProfile.named("logyard"));
        } else {
            EncoderConfig configured = config.encoders().get(name);
            if (configured instanceof JsonEncoderConfig json) {
                created = new JsonEncoder(resource, jsonProfile(config, json.profile()));
            } else if (configured instanceof ProviderEncoderConfig custom) {
                EventEncoderProvider provider = ProviderResolver.resolve(
                        extensions.encoders(),
                        custom.providerReference(),
                        "encoder '" + name + "'",
                        EventEncoderProvider::configurationSpec);
                created = ComponentInvocationBoundary.call(
                        "encoder '" + name + "' provider creation",
                        () -> Objects.requireNonNull(
                                provider.create(custom.providerReference().configuration()),
                                "encoder provider returned null: " + name));
            } else {
                throw new IllegalArgumentException("unknown encoder definition '" + name + "'");
            }
        }
        EventEncoder resolved = created;
        return ComponentInvocationBoundary.call(
                "encoder '" + Objects.requireNonNullElse(name, "logyard") + "' initialization",
                // Built-in JSON enforces its own bounds; keep its identity for output-owned UTF-8 encoding.
                () -> resolved instanceof JsonEncoder ? resolved : ExtensionGuardrails.encoder(resolved));
    }

    /**
     * Builds immutable service and resource attributes for resource-aware outputs.
     *
     * @param config complete Logyard configuration
     * @return resource attributes
     */
    public static ResourceAttributes resource(LogyardConfig config) {
        Objects.requireNonNull(config, "config");
        return ResourceAttributes.service(
                config.service().name(),
                config.service().namespace(),
                config.service().environment(),
                config.service().version(),
                config.service().instanceId(),
                config.resource().attributes(), config.resource()::includes);
    }

    /**
     * Resolves a built-in or configured JSON profile.
     *
     * @param config complete Logyard configuration
     * @param name JSON profile name
     * @return resolved JSON profile
     */
    public static JsonProfile jsonProfile(LogyardConfig config, String name) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(name, "name");
        JsonProfileConfig configured = config.jsonProfiles().get(name);
        if (configured == null) {
            return JsonProfile.named(name);
        }

        JsonAttributeTransformConfig attributes = configured.attributes();
        JsonAttributeTransform transform = new JsonAttributeTransform(
                JsonAttributeTransform.Mode.parse(attributes.mode()),
                attributes.prefix(),
                attributes.include(),
                attributes.exclude(),
                attributes.rename());
        return JsonProfile.custom(configured.name(), configured.preset(), configured.rename(), configured.drop(), transform);
    }

    /** Validates every JSON profile and provider-backed encoder definition without creating encoder instances. */
    public static void validateDefinitions(LogyardConfig config, ExtensionRegistry extensions) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(extensions, "extensions");
        for (JsonProfileConfig profile : config.jsonProfiles().values()) {
            jsonProfile(config, profile.name());
        }
        for (EncoderConfig encoder : config.encoders().values()) {
            if (encoder instanceof ProviderEncoderConfig custom) {
                ProviderResolver.resolve(
                        extensions.encoders(),
                        custom.providerReference(),
                        "encoder '" + custom.name() + "'",
                        EventEncoderProvider::configurationSpec);
            }
        }
    }
}
