package com.logyard4j.runtime.tools;

import com.logyard4j.config.loading.result.LoadedConfiguration;
import com.logyard4j.config.loading.LogyardConfigLoader;
import com.logyard4j.config.loading.overlay.ConfigOverlays;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/** Validates one configuration file exactly as the runtime would load it. */
final class ConfigValidateCommand {
    private ConfigValidateCommand() {
    }

    static int run(ToolArguments arguments, PrintStream out) throws IOException {
        Path path = Path.of(arguments.requiredPath("configuration file"));
        ConfigOverlays overlays = LogyardConfigTool.overlays(arguments.flag("profile"));
        LoadedConfiguration loaded = LogyardConfigLoader.loadDetailed(path, overlays);
        out.println("OK " + path);
        out.println("  profile: " + (loaded.activeProfile() == null ? "(none)" : loaded.activeProfile()));
        out.println("  profiles validated: " + (loaded.availableProfiles().isEmpty()
                ? "(none declared)"
                : String.join(", ", loaded.availableProfiles())));
        out.println("  overrides applied: " + overlays.overrides().size());
        out.println("  outputs: " + loaded.config().outputs().size()
                + ", logger rules: " + (loaded.config().loggers().size() + 1)
                + ", enrichers: " + loaded.config().enrichers().size()
                + ", filters: " + loaded.config().filters().size());
        return 0;
    }
}
