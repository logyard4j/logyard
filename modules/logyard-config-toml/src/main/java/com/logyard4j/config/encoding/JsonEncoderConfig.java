package com.logyard4j.config.encoding;

import com.logyard4j.config.validation.ConfigNames;

/** Built-in JSON encoder bound to one named profile. */
public record JsonEncoderConfig(String name, String profile) implements EncoderConfig {
    public JsonEncoderConfig {
        name = ConfigNames.component(name, "encoder name");
        profile = ConfigNames.component(profile, "JSON profile reference");
    }
}
