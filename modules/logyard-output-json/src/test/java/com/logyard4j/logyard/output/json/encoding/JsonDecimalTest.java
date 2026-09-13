package com.logyard4j.logyard.output.json.encoding;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JsonDecimalTest {
    @Test
    void integerExtremesDecimalBoundariesAndRandomValuesMatchTheJdk() {
        verify(Long.MIN_VALUE);
        verify(Long.MAX_VALUE);
        verify(0);
        long power = 1;
        for (int exponent = 0; exponent <= 18; exponent++) {
            for (int offset = -1; offset <= 1; offset++) {
                verify(power + offset);
                verify(-power + offset);
            }
            if (exponent < 18) power *= 10;
        }
        SplittableRandom random = new SplittableRandom(0x444543494d414cL);
        for (int sample = 0; sample < 10_000; sample++) verify(random.nextLong());
    }

    @Test
    void decimalWritesRespectExactRemainingCapacity() {
        for (long number : new long[] {0, 9, 10, -9, -10, Long.MIN_VALUE, Long.MAX_VALUE}) {
            int digits = Long.toString(number).length();
            JsonBuffer exact = new JsonBuffer(1, digits);
            exact.append(number);
            assertEquals(Long.toString(number), exact.result());
            assertThrows(JsonLimitExceeded.class, () -> exact.append(0L));

            JsonBuffer shortBuffer = new JsonBuffer(1, digits - 1);
            assertThrows(JsonLimitExceeded.class, () -> shortBuffer.append(number));
            assertEquals(0, shortBuffer.length());

            JsonUtf8Buffer bytes = new JsonUtf8Buffer();
            bytes.append("x".repeat(JsonOutputLimits.MAX_RECORD_CHARACTERS - digits));
            bytes.append(number);
            assertEquals(JsonOutputLimits.MAX_RECORD_CHARACTERS, bytes.length());
            assertThrows(JsonLimitExceeded.class, () -> bytes.append(0L));
            assertEquals(JsonOutputLimits.MAX_RECORD_CHARACTERS, bytes.length());
        }
    }

    private static void verify(long number) {
        String expected = Long.toString(number);
        assertEquals(expected.length(), JsonDecimal.length(number));
        JsonBuffer text = new JsonBuffer(32, 32);
        JsonUtf8Buffer bytes = new JsonUtf8Buffer();
        text.append(number);
        bytes.append(number);
        assertEquals(expected, text.result());
        assertEquals(expected, new String(bytes.bytes(), 0, bytes.length(), StandardCharsets.UTF_8));
    }
}
