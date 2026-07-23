package com.zsumz.logyard.config;

import com.zsumz.logyard.api.format.TextTemplate;

/** Built-in validated one-line formatter definition. */
public record TemplateFormatterConfig(String name, String template) implements FormatterConfig {
    public TemplateFormatterConfig {
        name = ConfigNames.component(name, "formatter name");
        template = TextTemplate.compile(template).source();
    }
}
