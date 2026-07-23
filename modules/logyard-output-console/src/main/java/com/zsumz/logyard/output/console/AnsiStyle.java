package com.zsumz.logyard.output.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Immutable semantic terminal style. */
public record AnsiStyle(
        String foreground,
        String background,
        boolean bold,
        boolean dim,
        boolean italic,
        boolean underline) {
    public static final AnsiStyle PLAIN = new AnsiStyle(null, null, false, false, false, false);
    static final String RESET = "\u001B[0m";

    public AnsiStyle {
        foreground = normalize(foreground);
        background = normalize(background);
        validateColor(foreground);
        validateColor(background);
    }

    public String render(String value, boolean enabled) {
        return render(value, enabled, ColorCapability.TRUECOLOR);
    }

    public String render(String value, boolean enabled, ColorCapability capability) {
        Objects.requireNonNull(value, "value");
        if (!enabled || equals(PLAIN)) {
            return value;
        }
        return prefix(Objects.requireNonNull(capability, "capability")) + value + RESET;
    }

    String prefix(ColorCapability capability) {
        List<String> codes = new ArrayList<>(6);
        if (bold) {
            codes.add("1");
        }
        if (dim) {
            codes.add("2");
        }
        if (italic) {
            codes.add("3");
        }
        if (underline) {
            codes.add("4");
        }
        appendColor(codes, foreground, false, capability);
        appendColor(codes, background, true, capability);
        return codes.isEmpty() ? "" : "\u001B[" + String.join(";", codes) + "m";
    }

    private static void validateColor(String color) {
        if (color == null) {
            return;
        }
        List<String> probe = new ArrayList<>(1);
        appendColor(probe, color, false, ColorCapability.TRUECOLOR);
    }

    private static String normalize(String color) {
        if (color == null || color.isBlank()) {
            return null;
        }
        return color.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static void appendColor(
            List<String> codes, String color, boolean background, ColorCapability capability) {
        if (color == null) {
            return;
        }
        if (color.matches("#[0-9a-f]{6}")) {
            int red = Integer.parseInt(color.substring(1, 3), 16);
            int green = Integer.parseInt(color.substring(3, 5), 16);
            int blue = Integer.parseInt(color.substring(5, 7), 16);
            appendRgb(codes, red, green, blue, background, capability);
            return;
        }
        if (color.matches("[0-9]{1,3}")) {
            int index = Integer.parseInt(color);
            if (index > 255) {
                throw new IllegalArgumentException("ANSI color index must be between 0 and 255: " + color);
            }
            if (capability == ColorCapability.ANSI16) {
                int[] rgb = xtermRgb(index);
                codes.add(Integer.toString(ansi16Code(nearestAnsi16(rgb[0], rgb[1], rgb[2]), background)));
            } else {
                codes.add((background ? "48" : "38") + ";5;" + index);
            }
            return;
        }
        Integer named = namedColor(color, background);
        if (named == null) {
            throw new IllegalArgumentException("unsupported terminal color: " + color);
        }
        codes.add(named.toString());
    }


    private static void appendRgb(
            List<String> codes,
            int red,
            int green,
            int blue,
            boolean background,
            ColorCapability capability) {
        switch (capability) {
            case TRUECOLOR -> codes.add((background ? "48" : "38")
                    + ";2;" + red + ";" + green + ";" + blue);
            case ANSI256 -> codes.add((background ? "48" : "38")
                    + ";5;" + nearestXterm256(red, green, blue));
            case ANSI16 -> codes.add(Integer.toString(
                    ansi16Code(nearestAnsi16(red, green, blue), background)));
        }
    }

    private static int nearestXterm256(int red, int green, int blue) {
        int redIndex = nearestCube(red);
        int greenIndex = nearestCube(green);
        int blueIndex = nearestCube(blue);
        int cube = 16 + 36 * redIndex + 6 * greenIndex + blueIndex;
        int cubeRed = cubeValue(redIndex);
        int cubeGreen = cubeValue(greenIndex);
        int cubeBlue = cubeValue(blueIndex);
        int grayIndex = Math.max(0, Math.min(23, Math.round((red + green + blue) / 3.0f - 8) / 10));
        int gray = 8 + grayIndex * 10;
        int cubeDistance = distance(red, green, blue, cubeRed, cubeGreen, cubeBlue);
        int grayDistance = distance(red, green, blue, gray, gray, gray);
        return grayDistance < cubeDistance ? 232 + grayIndex : cube;
    }

    private static int nearestCube(int value) {
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 0; index < 6; index++) {
            int distance = Math.abs(value - cubeValue(index));
            if (distance < bestDistance) {
                best = index;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static int cubeValue(int index) {
        return index == 0 ? 0 : 55 + index * 40;
    }

    private static int nearestAnsi16(int red, int green, int blue) {
        int[][] palette = {
                {0, 0, 0}, {205, 49, 49}, {13, 188, 121}, {229, 229, 16},
                {36, 114, 200}, {188, 63, 188}, {17, 168, 205}, {229, 229, 229},
                {102, 102, 102}, {241, 76, 76}, {35, 209, 139}, {245, 245, 67},
                {59, 142, 234}, {214, 112, 214}, {41, 184, 219}, {255, 255, 255}
        };
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 0; index < palette.length; index++) {
            int[] candidate = palette[index];
            int distance = distance(red, green, blue, candidate[0], candidate[1], candidate[2]);
            if (distance < bestDistance) {
                best = index;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static int ansi16Code(int index, boolean background) {
        int base = background ? 40 : 30;
        return index < 8 ? base + index : base + 60 + index - 8;
    }

    private static int[] xtermRgb(int index) {
        if (index < 16) {
            int[][] palette = {
                    {0, 0, 0}, {205, 49, 49}, {13, 188, 121}, {229, 229, 16},
                    {36, 114, 200}, {188, 63, 188}, {17, 168, 205}, {229, 229, 229},
                    {102, 102, 102}, {241, 76, 76}, {35, 209, 139}, {245, 245, 67},
                    {59, 142, 234}, {214, 112, 214}, {41, 184, 219}, {255, 255, 255}
            };
            return palette[index].clone();
        }
        if (index < 232) {
            int value = index - 16;
            return new int[] {
                    cubeValue(value / 36),
                    cubeValue((value / 6) % 6),
                    cubeValue(value % 6)
            };
        }
        int gray = 8 + (index - 232) * 10;
        return new int[] {gray, gray, gray};
    }

    private static int distance(int red, int green, int blue, int otherRed, int otherGreen, int otherBlue) {
        int redDelta = red - otherRed;
        int greenDelta = green - otherGreen;
        int blueDelta = blue - otherBlue;
        return redDelta * redDelta + greenDelta * greenDelta + blueDelta * blueDelta;
    }

    private static Integer namedColor(String color, boolean background) {
        boolean bright = color.startsWith("bright_");
        String base = bright ? color.substring("bright_".length()) : color;
        int offset = switch (base) {
            case "black" -> 0;
            case "red" -> 1;
            case "green" -> 2;
            case "yellow" -> 3;
            case "blue" -> 4;
            case "magenta", "purple" -> 5;
            case "cyan" -> 6;
            case "white" -> 7;
            case "gray", "grey" -> {
                bright = true;
                yield 0;
            }
            default -> -1;
        };
        if (offset < 0) {
            return null;
        }
        int baseCode = background ? 40 : 30;
        if (bright) {
            baseCode += 60;
        }
        return baseCode + offset;
    }
}
