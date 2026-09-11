package com.logyard4j.runtime.tools;

import java.io.IOException;
import java.io.PrintStream;

/** Converts a Logback XML configuration to Logyard TOML. */
final class LogbackMigrateCommand {
    private LogbackMigrateCommand() {
    }

    static int run(ToolArguments arguments, PrintStream out, PrintStream err) throws IOException {
        LogbackMigration.Result result =
                LogbackMigration.migrate(MigrationEmitter.read(arguments, "logback.xml file"));
        return MigrationEmitter.emit(
                result.toml(), result.notes(), result.valid(), result.validationError(), result.outcome(), arguments, out, err);
    }
}
