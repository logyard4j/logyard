package com.zsumz.logyard.runtime.tools;

import com.zsumz.logyard.config.loading.source.BoundedConfigurationFile;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Converts a Logback XML configuration to Logyard TOML. */
final class LogbackMigrateCommand {
    private LogbackMigrateCommand() {
    }

    static int run(ToolArguments arguments, PrintStream out, PrintStream err) throws IOException {
        Path input = Path.of(arguments.requiredPath("logback.xml file"));
        byte[] bytes = BoundedConfigurationFile.read(input);
        LogbackMigration.Result result = LogbackMigration.migrate(bytes);
        for (String note : result.notes()) {
            err.println("NOTE: " + note);
        }
        if (!result.valid()) {
            err.println("WARNING: the generated configuration failed validation: " + result.validationError());
        }
        String output = arguments.flag("output");
        if (output == null) {
            out.print(result.toml());
        } else {
            try {
                Files.write(Path.of(output), result.toml().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException exists) {
                throw new IllegalArgumentException("refusing to overwrite existing file: " + output);
            }
            out.println("wrote " + output);
        }
        return result.valid() ? 0 : 1;
    }
}
