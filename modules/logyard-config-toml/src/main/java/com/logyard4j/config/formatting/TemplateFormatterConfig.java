package com.logyard4j.config.formatting;

import com.logyard4j.api.format.TextTemplate;
import com.logyard4j.config.validation.ConfigNames;

/** Built-in validated one-line formatter definition. */
public record TemplateFormatterConfig(String name, String template) implements FormatterConfig {
    public TemplateFormatterConfig {
        name = ConfigNames.component(name, "formatter name");
        template = TextTemplate.compile(template).source();
    }
}
