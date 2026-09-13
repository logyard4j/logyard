package com.logyard4j.logyard.runtime.tools;

import com.logyard4j.logyard.config.ConfigurationException;
import com.logyard4j.logyard.config.loading.overlay.ConfigOverlays;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

/**
 * Offline Logyard configuration tool.
 *
 * <p>Run from the application classpath, no runtime required:</p>
 *
 * <pre>{@code java -cp <classpath> com.logyard4j.logyard.runtime.tools.LogyardConfigTool validate logyard.toml}</pre>
 *
 * <p>Commands: {@code validate <file>} checks a configuration exactly as the runtime
 * would, including every declared profile and the process overlays; {@code explain
 * <file>} prints the effective configuration with the origin of every value;
 * {@code schema} prints the machine-readable key vocabulary; {@code migrate-logback
 * <logback.xml>} and {@code migrate-log4j2 <log4j2.xml>} convert an existing
 * configuration. Exit codes: 0 success, 1 invalid configuration, 2 usage or I/O
 * failure, 3 strict migration refused.</p>
 */
public final class LogyardConfigTool {
    private LogyardConfigTool() {
    }

    /** Runs one command and exits with its status code. */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            return dispatch(args, out, err);
        } catch (ConfigurationException invalid) {
            err.println("invalid configuration: " + invalid.getMessage());
            return 1;
        } catch (IllegalArgumentException usage) {
            err.println(usage.getMessage());
            err.println();
            usage(err);
            return 2;
        } catch (IOException failure) {
            err.println("cannot read input: " + failure.getMessage());
            return 2;
        }
    }

    private static int dispatch(String[] args, PrintStream out, PrintStream err) throws IOException {
        if (args.length == 0) {
            usage(err);
            return 2;
        }
        return switch (args[0]) {
            case "validate" -> ConfigValidateCommand.run(
                    ToolArguments.parse(args, 1, List.of("profile")), out);
            case "explain" -> ConfigExplainCommand.run(
                    ToolArguments.parse(args, 1, List.of("profile", "logger", "key")), out);
            case "schema" -> ConfigSchemaCommand.run(ToolArguments.parse(args, 1, List.of()), out);
            case "migrate-logback" -> LogbackMigrateCommand.run(
                    ToolArguments.parse(args, 1, List.of("output", "strict")), out, err);
            case "migrate-log4j2" -> Log4j2MigrateCommand.run(
                    ToolArguments.parse(args, 1, List.of("output", "strict")), out, err);
            case "help", "--help", "-h" -> {
                usage(out);
                yield 0;
            }
            default -> throw new IllegalArgumentException("unknown command '" + args[0] + "'");
        };
    }

    /** Returns the process overlays with an optional {@code --profile} selection applied. */
    static ConfigOverlays overlays(String profileFlag) {
        ConfigOverlays captured = ConfigOverlays.fromProcess(System.getenv(), System.getProperties());
        return profileFlag == null ? captured : new ConfigOverlays(profileFlag, captured.overrides());
    }

    private static void usage(PrintStream stream) {
        stream.println("""
                Logyard configuration tool

                  validate <file> [--profile <name>]
                      Check the configuration exactly as the runtime would, including
                      every declared profile and the process overrides. Exit 0 when valid.

                  explain <file> [--profile <name>] [--logger <name>] [--key <path>]
                      Print the effective configuration and where every value came from:
                      file line, profile, or override.

                  schema
                      Print the machine-readable configuration key vocabulary as JSON.

                  migrate-logback <logback.xml> [--output <file>] [--strict]
                  migrate-log4j2 <log4j2.xml> [--output <file>] [--strict]
                      Convert an existing configuration to logyard.toml, reporting every
                      construct that has no equivalent. Strict mode writes only exact conversions.

                Exit codes: 0 success, 1 invalid configuration, 2 usage or I/O failure, 3 strict migration refused.""");
    }
}
