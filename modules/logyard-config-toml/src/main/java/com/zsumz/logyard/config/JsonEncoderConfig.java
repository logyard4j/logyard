package com.zsumz.logyard.config;

/** Built-in JSON encoder bound to one named profile. */
public record JsonEncoderConfig(String name, String profile) implements EncoderConfig {
    public JsonEncoderConfig {
        name = ConfigNames.component(name, "encoder name");
        profile = ConfigNames.component(profile, "JSON profile reference");
    }
}
