package com.zsumz.logyard.compare;

import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.layout.template.json.JsonTemplateLayout;

import java.nio.charset.StandardCharsets;

/** Configures Log4j's shipped JSON Template Layout to emit the matched comparison fields. */
final class NativeJson {
    private NativeJson() {
    }

    static JsonTemplateLayout create(Configuration configuration, RunOptions options) {
        String attributes = options.fields() == 0 ? "" : ",\"attributes\":{\"$resolver\":\"mdc\"}";
        String template = """
                {"timestamp":{"$resolver":"timestamp","pattern":{
                   "format":"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'","timeZone":"UTC"}},
                 "level":{"$resolver":"level","field":"name"},
                 "message":{"$resolver":"message","stringified":true}%s}
                """.formatted(attributes);
        return JsonTemplateLayout.newBuilder().setConfiguration(configuration)
                .setCharset(StandardCharsets.UTF_8).setEventTemplate(template).setEventDelimiter("\n")
                .setLocationInfoEnabled(false).setStackTraceEnabled(false).build();
    }
}
