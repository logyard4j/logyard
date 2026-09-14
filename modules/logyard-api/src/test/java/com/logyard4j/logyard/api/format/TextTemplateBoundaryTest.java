package com.logyard4j.logyard.api.format;

import com.logyard4j.logyard.api.event.CaptureLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TextTemplateBoundaryTest {
    @Test
    void aFullResultDoesNotInvokeAnyFurtherResolver() {
        for (String separator : List.of("", "x")) {
            String message = "m".repeat(CaptureLimits.MAX_TEXT_CHARS - separator.length());
            String rendered = TextTemplate.compile("{message}" + separator + "{fields}").render(name -> {
                if (name.equals("message")) return message;
                throw new AssertionError("an exhausted template must not resolve " + name);
            });
            assertEquals(message + separator, rendered);
        }
    }

    @Test
    void aCharacterThatCannotFitEndsRenderingWithoutSplittingIt() {
        String message = "m".repeat(CaptureLimits.MAX_TEXT_CHARS - 1);
        String rendered = TextTemplate.compile("{message}{fields}{event}").render(name -> switch (name) {
            case "message" -> message;
            case "fields" -> "\ud83d\ude80";
            default -> throw new AssertionError("no further placeholder can fit");
        });
        assertEquals(message, rendered);
    }

    @ParameterizedTest
    @ValueSource(strings = {"before\u2028after", "before\u2029after"})
    void oneLineTemplatesRejectUnicodeLineAndParagraphSeparators(String source) {
        assertThrows(IllegalArgumentException.class, () -> TextTemplate.compile(source));
    }
}
