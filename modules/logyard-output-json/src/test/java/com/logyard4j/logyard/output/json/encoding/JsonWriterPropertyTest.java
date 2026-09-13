package com.logyard4j.logyard.output.json.encoding;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JsonWriterPropertyTest {
    private static final long SEED = 0x4c4f47594152444cL;

    @Test
    void randomizedUtf16StringsRoundTripThroughJsonEscaping() {
        SplittableRandom random = new SplittableRandom(SEED);
        JsonBuffer buffer = new JsonBuffer(256, JsonOutputLimits.MAX_RECORD_CHARACTERS);
        JsonWriter writer = new JsonWriter(buffer);
        for (int sample = 0; sample < 2_000; sample++) {
            String source = randomString(random, random.nextInt(0, 257));

            writer.reset();
            writer.string(source);
            String encoded = buffer.result();

            assertEquals(source, decodeJsonString(encoded));
            assertContainsNoRawControlCharacters(encoded);
        }
    }

    private static String randomString(SplittableRandom random, int length) {
        StringBuilder value = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            char next = switch (random.nextInt(8)) {
                case 0 -> (char) random.nextInt(0x20);
                case 1 -> '"';
                case 2 -> '\\';
                case 3 -> (char) random.nextInt(0xd800, 0xe000);
                default -> (char) random.nextInt(Character.MAX_VALUE + 1);
            };
            value.append(next);
        }
        return value.toString();
    }

    private static String decodeJsonString(String encoded) {
        assertTrue(encoded.length() >= 2 && encoded.charAt(0) == '"' && encoded.charAt(encoded.length() - 1) == '"');
        StringBuilder decoded = new StringBuilder(encoded.length() - 2);
        for (int index = 1; index < encoded.length() - 1; index++) {
            char current = encoded.charAt(index);
            if (current != '\\') {
                decoded.append(current);
                continue;
            }
            char escape = encoded.charAt(++index);
            switch (escape) {
                case '"' -> decoded.append('"');
                case '\\' -> decoded.append('\\');
                case 'b' -> decoded.append('\b');
                case 'f' -> decoded.append('\f');
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'u' -> {
                    int codeUnit = Integer.parseInt(encoded.substring(index + 1, index + 5), 16);
                    decoded.append((char) codeUnit);
                    index += 4;
                }
                default -> throw new AssertionError("unsupported JSON escape: \\" + escape);
            }
        }
        return decoded.toString();
    }

    private static void assertContainsNoRawControlCharacters(String encoded) {
        for (int index = 1; index < encoded.length() - 1; index++) {
            int offset = index;
            assertTrue(encoded.charAt(index) >= 0x20, () -> "raw control character at offset " + offset);
        }
    }
}
