package com.logyard4j.logyard.config.formatting;

import com.logyard4j.logyard.api.format.TextTemplate;
import com.logyard4j.logyard.config.validation.ConfigNames;

/** Built-in validated one-line formatter definition. */
public record TemplateFormatterConfig(String name, String template) implements FormatterConfig {
    public TemplateFormatterConfig {
        name = ConfigNames.component(name, "formatter name");
        template = TextTemplate.compile(template).source();
    }
}
