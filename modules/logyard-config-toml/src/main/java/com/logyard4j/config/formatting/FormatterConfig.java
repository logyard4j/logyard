package com.logyard4j.config.formatting;

/** One named text-formatter definition. */
public sealed interface FormatterConfig permits TemplateFormatterConfig, ProviderFormatterConfig {
    String name();
}
