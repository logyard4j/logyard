package com.logyard4j.logyard.config.loading;

import com.logyard4j.logyard.config.ConfigurationException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies that semantic configuration diagnostics carry lines and scoped suggestions. */
public final class ConfigDiagnosticsTest {
    @Test
    void unknownKeyReportsLineAndScopedSuggestion() {
        String message = failure("""
                schema = 1
                [delivery]
                capactiy = 32
                [outputs.console]
                type = "console"
                """);
        check(message.startsWith("bad.toml:3: "), "line of the misspelled key", message);
        check(message.contains("delivery.capactiy"), "path of the misspelled key", message);
        check(message.contains("did you mean 'capacity'?"), "scoped suggestion", message);
    }

    @Test
    void unknownKeySuggestionsDoNotCrossSections() {
        String message = failure("""
                schema = 1
                [service]
                level = "info"
                [outputs.console]
                type = "console"
                """);
        check(message.startsWith("bad.toml:3: "), "line of the foreign key", message);
        check(!message.contains("did you mean 'level'"), "no self-suggestion from another section", message);
    }

    @Test
    void unknownRootSectionReportsHeaderLineAndSuggestion() {
        String message = failure("""
                schema = 1
                [outputs.console]
                type = "console"
                [logers]
                root = { level = "info" }
                """);
        check(message.startsWith("bad.toml:4: "), "line of the misspelled section header", message);
        check(message.contains("did you mean 'loggers'?"), "root section suggestion", message);
    }

    @Test
    void nestedInlineTableErrorsPointAtTheAssignmentLine() {
        String message = failure("""
                schema = 1
                [outputs.app]
                type = "file"
                path = "app.jsonl"
                rotate = { size = 5 }
                """);
        check(message.startsWith("bad.toml:5: "), "line of the inline rotate assignment", message);
        check(message.contains("outputs.app.rotate.size"), "path of the nested key", message);
    }

    @Test
    void subTableValueErrorsPointAtTheExactKeyLine() {
        String message = failure("""
                schema = 1
                [outputs.console]
                type = "console"
                [outputs.console.color]
                mode = "sometimes"
                """);
        check(message.startsWith("bad.toml:5: "), "line of the invalid color mode", message);
        check(message.contains("must be auto, always, or never"), "value diagnostic", message);
    }

    @Test
    void arrayElementErrorsPointAtTheArrayAssignmentLine() {
        String message = failure("""
                schema = 1
                [context]
                mdc = [1]
                [outputs.console]
                type = "console"
                """);
        check(message.startsWith("bad.toml:3: "), "line of the array assignment", message);
        check(message.contains("context.mdc[0]"), "indexed element path", message);
    }

    @Test
    void environmentExpansionErrorsPointAtTheValueLine() {
        String message = failure("""
                schema = 1
                [service]
                name = "orders"
                environment = "${MISSING_LOGYARD_TEST_VARIABLE}"
                [outputs.console]
                type = "console"
                """);
        check(message.startsWith("bad.toml:4: "), "line of the unresolved reference", message);
        check(message.contains("MISSING_LOGYARD_TEST_VARIABLE"), "variable name", message);
    }

    @Test
    void sectionLevelValidationFailuresDoNotDoubleThePath() {
        String message = failure("""
                schema = 1
                [delivery]
                mode = "turbo"
                [outputs.console]
                type = "console"
                """);
        check(message.contains(": delivery: "), "section-level path", message);
        check(!message.contains("delivery.delivery"), "no doubled section segment", message);
    }

    @Test
    void unknownOutputReferenceReportsTheReferencingLine() {
        String message = failure("""
                schema = 1
                [outputs.console]
                type = "console"
                [loggers]
                root = { level = "info", outputs = ["consle"] }
                """);
        check(message.startsWith("bad.toml:5: "), "line of the root logger rule", message);
        check(message.contains("unknown output 'consle'"), "reference diagnostic", message);
    }

    private static String failure(String text) {
        try {
            LogyardConfigLoader.parse(text, "bad.toml", Path.of("."), Map.of());
            throw new AssertionError("configuration should fail");
        } catch (ConfigurationException expected) {
            return expected.getMessage();
        }
    }

    private static void check(boolean condition, String expectation, String message) {
        if (!condition) {
            throw new AssertionError("expected " + expectation + " in diagnostic: " + message);
        }
    }
}
