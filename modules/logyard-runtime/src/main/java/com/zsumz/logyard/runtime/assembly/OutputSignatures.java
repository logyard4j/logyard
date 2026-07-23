package com.zsumz.logyard.runtime.assembly;

import com.zsumz.logyard.config.output.ConsoleOutputConfig;
import com.zsumz.logyard.config.output.CustomOutputConfig;
import com.zsumz.logyard.config.encoding.EncoderConfig;
import com.zsumz.logyard.config.formatting.FormatterConfig;
import com.zsumz.logyard.config.encoding.JsonEncoderConfig;
import com.zsumz.logyard.config.output.JsonFileOutputConfig;
import com.zsumz.logyard.config.encoding.JsonProfileConfig;
import com.zsumz.logyard.config.output.JsonStreamOutputConfig;
import com.zsumz.logyard.config.LogyardConfig;
import com.zsumz.logyard.config.output.OutputConfig;
import com.zsumz.logyard.config.theme.ThemeConfig;

import java.nio.file.Path;

/** Derives the complete construction evidence used to decide whether an output is reusable. */
final class OutputSignatures {
    private OutputSignatures() {
    }

    static OutputSignature from(LogyardConfig config, OutputConfig output) {
        FormatterConfig formatter = null;
        EncoderConfig encoder = null;
        JsonProfileConfig profile = null;
        ThemeConfig theme = null;
        if (output instanceof ConsoleOutputConfig console) {
            formatter = console.formatter() == null ? null : config.formatters().get(console.formatter());
            theme = config.themes().get(console.color().theme());
        } else if (output instanceof JsonStreamOutputConfig stream) {
            encoder = encoderConfig(config, stream.encoder());
            profile = profileConfig(config, encoder);
        } else if (output instanceof JsonFileOutputConfig file) {
            encoder = encoderConfig(config, file.encoder());
            profile = profileConfig(config, encoder);
        } else if (output instanceof CustomOutputConfig custom) {
            formatter = custom.formatter() == null ? null : config.formatters().get(custom.formatter());
            encoder = encoderConfig(config, custom.encoder());
            profile = profileConfig(config, encoder);
        }

        boolean resourceAware =
                output instanceof JsonFileOutputConfig || output instanceof JsonStreamOutputConfig || output instanceof CustomOutputConfig;
        return new OutputSignature(
                output,
                config.deliveryFor(output),
                resourceAware ? config.service() : null,
                resourceAware ? config.resource() : null,
                theme,
                formatter,
                encoder,
                profile,
                config.runtime().shutdownTimeout());
    }

    static Path exclusivePath(OutputConfig output) {
        return output instanceof JsonFileOutputConfig json ? json.path().toAbsolutePath().normalize() : null;
    }

    private static EncoderConfig encoderConfig(LogyardConfig config, String name) {
        return name == null ? null : config.encoders().get(name);
    }

    private static JsonProfileConfig profileConfig(LogyardConfig config, EncoderConfig encoder) {
        return encoder instanceof JsonEncoderConfig json ? config.jsonProfiles().get(json.profile()) : null;
    }
}
