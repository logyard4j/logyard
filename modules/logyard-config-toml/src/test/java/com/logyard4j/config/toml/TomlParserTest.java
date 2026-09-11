package com.logyard4j.config.toml;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TomlParserTest {
    @Test
    void composesDottedKeysArrayTablesUnicodeAndNumericValues() {
        TomlDocument document = TomlParser.parse("direct.toml", """
                title = "Log\\u0079ard"
                limits = { hex = 0x10, ratio = 1.5 }
                [[outputs]]
                name = "first"
                [[outputs]]
                name = "second"
                """);

        assertEquals("Logyard", document.root().get("title"));
        assertEquals(Map.of("hex", 16L, "ratio", 1.5), document.root().get("limits"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outputs = (List<Map<String, Object>>) document.root().get("outputs");
        assertEquals(List.of("first", "second"), outputs.stream().map(output -> output.get("name")).toList());
    }

    @Test
    void retainsPreciseSourceCoordinatesAcrossExtractedParserComponents() {
        TomlParseException failure = assertThrows(
                TomlParseException.class,
                () -> TomlParser.parse("broken.toml", "valid = 1\ninvalid = 01\n"));

        assertTrue(failure.getMessage().contains("broken.toml:2:13"));
        assertTrue(failure.getMessage().contains("invalid integer syntax"));
    }
}
