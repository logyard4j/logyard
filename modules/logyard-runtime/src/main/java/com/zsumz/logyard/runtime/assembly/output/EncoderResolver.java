package com.zsumz.logyard.runtime.assembly.output;

import com.zsumz.logyard.api.spi.encoding.EventEncoder;
import com.zsumz.logyard.api.spi.encoding.EventEncoderProvider;
import com.zsumz.logyard.config.EncoderConfig;
import com.zsumz.logyard.config.JsonAttributeTransformConfig;
import com.zsumz.logyard.config.JsonEncoderConfig;
import com.zsumz.logyard.config.JsonProfileConfig;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.ProviderEncoderConfig;
import com.zsumz.logyard.output.json.encoding.JsonAttributeTransform;
import com.zsumz.logyard.output.json.encoding.JsonEncoder;
import com.zsumz.logyard.output.json.encoding.JsonProfile;
import com.zsumz.logyard.output.json.encoding.ResourceAttributes;
import com.zsumz.logyard.runtime.extension.ExtensionGuardrails;
import com.zsumz.logyard.runtime.extension.ExtensionRegistry;
import com.zsumz.logyard.runtime.extension.ProviderResolver;

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
     * @return guarded encoder
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
                created = Objects.requireNonNull(
                        provider.create(custom.providerReference().configuration()),
                        "encoder provider returned null: " + name);
            } else {
                throw new IllegalArgumentException("unknown encoder definition '" + name + "'");
            }
        }
        return ExtensionGuardrails.encoder(created);
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
                config.resource().attributes());
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
