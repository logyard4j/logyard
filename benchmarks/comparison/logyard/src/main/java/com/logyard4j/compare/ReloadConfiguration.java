package com.logyard4j.compare;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Two unchanged buffered file outputs shared by successive routing epochs. */
final class ReloadConfiguration {
    private ReloadConfiguration() {
    }

    static void write(Path source, boolean debug) throws IOException {
        Files.writeString(source, """
                schema = 1
                [runtime]
                watch = false
                shutdown_timeout = "5s"
                internal_status = "off"
                [delivery]
                mode = "async"
                [context]
                mdc = ["field.0", "field.1", "field.2", "field.3"]
                [loggers]
                root = { level = "%s", outputs = ["first", "second"] }
                [outputs.first]
                type = "file"
                path = "first.jsonl"
                buffer = "4KiB"
                flush = "10ms"
                encoder = "record"
                [outputs.second]
                type = "file"
                path = "second.jsonl"
                buffer = "4KiB"
                flush = "10ms"
                encoder = "record"
                [encoders.record]
                type = "json"
                profile = "record"
                [json_profiles.record]
                preset = "logyard"
                rename = { severity_text = "level", body = "message" }
                drop = ["observed_timestamp_unix_nano", "severity_number", "logger", "event_name",
                        "message_template", "resource", "thread"]
                """.formatted(debug ? "debug" : "info"));
    }
}
