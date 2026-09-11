package com.logyard4j.runtime.tools;

import com.logyard4j.config.loading.source.BoundedConfigurationFile;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Shared input reading and result emission for the configuration migration commands.
 *
 * <p>Input is bounded, the loss report goes to standard error so a piped TOML document
 * stays clean, and an existing output file is never overwritten.</p>
 */
final class MigrationEmitter {
    private MigrationEmitter() {
    }

    /** Reads the single positional input file. */
    static byte[] read(ToolArguments arguments, String description) throws IOException {
        Path input = Path.of(arguments.requiredPath(description));
        return BoundedConfigurationFile.read(input);
    }

    /** Reports the notes, writes the TOML, and returns the exit status of the migration. */
    static int emit(
            String toml,
            List<String> notes,
            boolean valid,
            String validationError,
            MigrationOutcome outcome,
            ToolArguments arguments,
            PrintStream out,
            PrintStream err) throws IOException {
        byte[] encoded = toml.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MigrationProperties.MAX_CHARACTERS) {
            throw new IllegalArgumentException("generated configuration exceeds 1MiB");
        }
        err.println("MIGRATION: " + outcome);
        for (String note : notes) {
            err.println("NOTE: " + note);
        }
        if (!valid) {
            err.println("WARNING: the generated configuration failed validation: " + validationError);
        }
        if (arguments.flag("strict") != null && outcome != MigrationOutcome.EXACT) {
            err.println("REFUSED: strict migration requires an exact conversion; no configuration was written");
            return 3;
        }
        String output = arguments.flag("output");
        if (output == null) {
            out.print(toml);
        } else {
            try {
                Files.write(Path.of(output), encoded,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException exists) {
                throw new IllegalArgumentException("refusing to overwrite existing file: " + output);
            }
            out.println("wrote " + output);
        }
        return valid ? 0 : 1;
    }
}
