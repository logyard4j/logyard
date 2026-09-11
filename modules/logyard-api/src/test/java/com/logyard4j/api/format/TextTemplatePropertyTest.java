package com.logyard4j.api.format;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TextTemplatePropertyTest {
    private static final long SEED = 0x54454d504c415445L;
    private static final List<String> PLACEHOLDERS =
            List.of("timestamp", "level", "logger", "thread", "event", "message", "fields");

    @Test
    void randomizedValidTemplatesCompileAndRenderLiterally() {
        SplittableRandom random = new SplittableRandom(SEED);
        for (int sample = 0; sample < 2_000; sample++) {
            GeneratedTemplate generated = generate(random);
            TextTemplate template = TextTemplate.compile(generated.source());

            String rendered = template.render(name -> "<" + name + ">");

            assertEquals(generated.rendered(), rendered);
            assertEquals(generated.source(), template.source());
        }
    }

    private static GeneratedTemplate generate(SplittableRandom random) {
        StringBuilder source = new StringBuilder();
        StringBuilder rendered = new StringBuilder();
        int segments = random.nextInt(1, 33);
        for (int index = 0; index < segments; index++) {
            switch (random.nextInt(4)) {
                case 0 -> {
                    source.append("{{");
                    rendered.append('{');
                }
                case 1 -> {
                    source.append("}}");
                    rendered.append('}');
                }
                case 2 -> appendLiteral(random, source, rendered);
                default -> {
                    String placeholder = PLACEHOLDERS.get(random.nextInt(PLACEHOLDERS.size()));
                    source.append('{').append(placeholder).append('}');
                    rendered.append('<').append(placeholder).append('>');
                }
            }
        }
        return new GeneratedTemplate(source.toString(), rendered.toString());
    }

    private static void appendLiteral(SplittableRandom random, StringBuilder source, StringBuilder rendered) {
        int length = random.nextInt(1, 17);
        for (int index = 0; index < length; index++) {
            char character;
            do {
                character = (char) random.nextInt(0x20, 0xd800);
            } while (character == '{' || character == '}' || Character.isISOControl(character));
            source.append(character);
            rendered.append(character);
        }
    }

    private record GeneratedTemplate(String source, String rendered) {
    }
}
