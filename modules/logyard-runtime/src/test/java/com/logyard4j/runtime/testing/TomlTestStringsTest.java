package com.logyard4j.runtime.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class TomlTestStringsTest {
    @Test
    void escapesWindowsPathsForTomlBasicStrings() {
        assertEquals(
                "C:\\\\Users\\\\runner\\\\log\\\"yard.jsonl",
                TomlTestStrings.escapeBasicString(Path.of("C:\\Users\\runner\\log\"yard.jsonl")));
    }

    @Test
    void escapesTomlControlCharacters() {
        assertEquals(
                "tab\\tline\\nquote\\\"slash\\\\delete\\u007F",
                TomlTestStrings.escapeBasicString("tab\tline\nquote\"slash\\delete\u007f"));
    }
}
