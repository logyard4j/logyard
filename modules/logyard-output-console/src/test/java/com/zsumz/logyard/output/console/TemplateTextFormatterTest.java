package com.zsumz.logyard.output.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zsumz.logyard.api.Level;
import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.event.CaptureLimits;
import com.zsumz.logyard.api.event.LogEvent;
import com.zsumz.logyard.api.format.TextTemplate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

final class TemplateTextFormatterTest {
    @Test
    void validatesTheNonExecutableTemplateGrammar() {
        assertThrows(IllegalArgumentException.class,
                () -> TextTemplate.compile("{logger}"));
        assertThrows(IllegalArgumentException.class,
                () -> TextTemplate.compile("{message} {unknown}"));
        assertThrows(IllegalArgumentException.class,
                () -> TextTemplate.compile("{message"));
        assertThrows(IllegalArgumentException.class,
                () -> TextTemplate.compile("{message}\n"));
        assertThrows(IllegalArgumentException.class,
                () -> TextTemplate.compile("{message}".repeat(65)));
    }

    @Test
    void rendersEscapedValuesAndLiteralBraces() {
        TemplateTextFormatter formatter = new TemplateTextFormatter(
                "{level} {{ {message} }} {fields}", ZoneOffset.UTC);
        String rendered = formatter.format(event(AttributeSet.builder()
                .put("request.id", "a\tb")
                .build()));

        assertEquals("INFO { hello world\\n } request.id=a\\tb", rendered);
    }

    @Test
    void fieldsAndFinalRenderingStayWithinTheTextCaptureBound() {
        AttributeSet.Builder attributes = AttributeSet.builder();
        for (int index = 0; index < 128; index++) {
            attributes.put("field." + index, "x".repeat(CaptureLimits.MAX_TEXT_CHARS));
        }
        TemplateTextFormatter formatter = new TemplateTextFormatter(
                "{message} {fields}", ZoneOffset.UTC);

        String rendered = formatter.format(event(attributes.build()));

        assertTrue(rendered.length() <= CaptureLimits.MAX_TEXT_CHARS);
    }

    private static LogEvent event(AttributeSet attributes) {
        return new LogEvent(
                0L,
                0L,
                Level.INFO,
                "com.acme.Service",
                "event",
                "hello {}",
                new Object[] {"world\n"},
                attributes,
                null,
                1L,
                "main");
    }
}
