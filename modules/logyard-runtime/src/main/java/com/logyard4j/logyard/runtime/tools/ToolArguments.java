package com.logyard4j.logyard.runtime.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal deterministic command-line parsing: positional values and {@code --flag value} pairs. */
final class ToolArguments {
    private final List<String> positional = new ArrayList<>();
    private final Map<String, String> flags = new LinkedHashMap<>();

    private ToolArguments() {
    }

    /**
     * Parses everything after the command name.
     *
     * @throws IllegalArgumentException for a flag without a value or outside the allowed set
     */
    static ToolArguments parse(String[] args, int from, List<String> allowedFlags) {
        ToolArguments result = new ToolArguments();
        for (int index = from; index < args.length; index++) {
            String argument = args[index];
            if (argument.startsWith("--")) {
                String name = argument.substring(2);
                if (!allowedFlags.contains(name)) {
                    throw new IllegalArgumentException("unknown option '--" + name + "'");
                }
                if (result.flags.containsKey(name)) {
                    throw new IllegalArgumentException("duplicate option '--" + name + "'");
                }
                if (name.equals("strict")) {
                    result.flags.put(name, "true");
                    continue;
                }
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("option '--" + name + "' requires a value");
                }
                result.flags.put(name, args[++index]);
            } else {
                result.positional.add(argument);
            }
        }
        return result;
    }

    String requiredPath(String description) {
        if (positional.size() != 1) {
            throw new IllegalArgumentException("expected exactly one " + description);
        }
        return positional.getFirst();
    }

    void requireNoValues() {
        if (!positional.isEmpty()) {
            throw new IllegalArgumentException("this command takes no arguments");
        }
    }

    String flag(String name) {
        return flags.get(name);
    }
}
