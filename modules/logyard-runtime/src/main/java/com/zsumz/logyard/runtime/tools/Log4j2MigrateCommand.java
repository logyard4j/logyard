package com.zsumz.logyard.runtime.tools;

import java.io.IOException;
import java.io.PrintStream;

/** Converts a Log4j2 XML configuration to Logyard TOML. */
final class Log4j2MigrateCommand {
    private Log4j2MigrateCommand() {
    }

    static int run(ToolArguments arguments, PrintStream out, PrintStream err) throws IOException {
        Log4j2Migration.Result result =
                Log4j2Migration.migrate(MigrationEmitter.read(arguments, "log4j2.xml file"));
        return MigrationEmitter.emit(
                result.toml(), result.notes(), result.valid(), result.validationError(), arguments, out, err);
    }
}
