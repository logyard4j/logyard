package com.zsumz.logyard.compare;

/** Shared JSON string escaping keeps the comparison's emitted work identical. */
public final class JsonText {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private JsonText() {
    }

    public static String quote(String value) {
        StringBuilder text = new StringBuilder(value.length() + 2);
        append(text, value);
        return text.toString();
    }

    public static void append(StringBuilder text, String value) {
        text.append('"');
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '"' || ch == '\\') text.append('\\').append(ch);
            else if (ch < 0x20) text.append("\\u00").append(HEX[ch >>> 4]).append(HEX[ch & 15]);
            else text.append(ch);
        }
        text.append('"');
    }
}
