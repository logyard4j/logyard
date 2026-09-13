package com.logyard4j.logyard.api.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MessageFormatPlanAlignmentTest {
    @ParameterizedTest
    @MethodSource("patterns")
    void preservesElementOrderAndIndependentChoiceSelections(String pattern, Object[] arguments) {
        String expected = new MessageFormat(pattern, Locale.getDefault(Locale.Category.FORMAT)).format(arguments);

        BoundedMessageFormat.Result result = BoundedMessageFormat.messageFormat(pattern, arguments);

        assertEquals(expected, result.message());
        assertFalse(result.formatFailed());
        assertFalse(result.truncated());
    }

    @Test
    void invalidRootSyntaxDoesNotCaptureCallerArguments() {
        AtomicInteger calls = new AtomicInteger();
        Object value = observedValue(calls);
        String pattern = "{0,unknown} {1}";

        BoundedMessageFormat.Result result = BoundedMessageFormat.messageFormat(pattern, new Object[] {value, value});

        assertEquals(0, calls.get());
        assertEquals(pattern, result.message());
        assertTrue(result.formatFailed());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidChoiceSyntaxIsOnlyInterpretedInTheSelectedBranch(boolean selected) {
        AtomicInteger calls = new AtomicInteger();
        String pattern = "{0,choice,0#safe|1#{1,unknown}}";

        BoundedMessageFormat.Result result = BoundedMessageFormat.messageFormat(
                pattern, new Object[] {selected ? 1 : 0, observedValue(calls)});

        assertEquals(selected ? 1 : 0, calls.get());
        assertEquals(selected ? pattern : "safe", result.message());
        assertEquals(selected, result.formatFailed());
    }

    private static Stream<Arguments> patterns() {
        return Stream.of(
                Arguments.of("{2,number,integer}|{0,choice,0#none|1#{1}}|{2,number,000}|{3,choice,0#zero|1#{4}}|{0}",
                        new Object[] {1, "left", 12, 1, "right"}),
                Arguments.of("{0,number,000}|{0,number,integer}|{0}|{0,choice,0#zero|1#one|1<many}",
                        new Object[] {12}),
                Arguments.of("'{0}'|{1}|''{2,number,000}''", new Object[] {null, "second", 7}),
                Arguments.of("{0,choice,0#none|1#{1}}|{1}|{2,choice,0#left|1#right}",
                        new Object[] {null, "visible", null}),
                Arguments.of("{1}|{3,choice,0#none|1#some}|{0,number,integer}", new Object[] {7, "shown"}),
                Arguments.of("{2,choice,0#nothing|1#{0,number,000}}|{1,choice,0#empty|1#{3}}",
                        new Object[] {7, 1, 1, "tail"}));
    }

    private static Object observedValue(AtomicInteger calls) {
        return new Object() {
            @Override
            public String toString() {
                calls.incrementAndGet();
                return "captured";
            }
        };
    }
}
