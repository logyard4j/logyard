# Configuration

[Logyard](README.md) · [Runtime](RUNTIME.md)

Start with `src/main/resources/logyard.toml`:

```toml
schema = 1

[loggers]
root = { level = "info", outputs = ["console"] }

[outputs.console]
type = "console"
stream = "stderr"
```

Add the settings below as needed. Replace an existing TOML table rather than declaring it twice. [logyard.toml](logyard.toml) has a console and file example.

## Select a configuration

Logyard checks a system property, an environment variable, the framework source, then `classpath:logyard.toml` and `./logyard.toml`.

```sh
java -Dlogyard.config=/etc/my-app/logyard.toml -jar my-app.jar
```

You can also set `LOGYARD_CONFIG`. Explicit sources can use `classpath:logging/logyard-prod.toml`; a missing or invalid explicit source fails startup.

Without a file, Logyard uses an INFO stderr console and async delivery. To require a file during discovery, use `-Dlogyard.config.required=true`.

Relative output paths use the config file's directory. Classpath sources use the application's base directory.

## Service identity

```toml
[service]
name = "checkout"
version = "${APP_VERSION:-local}"
environment = "${APP_ENVIRONMENT:-development}"

[resource.attributes]
region = "eu-west"
```

Use `${NAME}` for an environment variable or `${NAME:-fallback}` for a default. Service fields also include `namespace` and `instance_id`.

To omit resource metadata, use `[resource]` with `exclude = ["service.version"]`. An `include` list keeps only the named keys; exclusions win.

## Levels and routes

```toml
[loggers]
root = { level = "info", outputs = ["console", "json"] }
"com.example" = { level = "debug" }
"com.example.noisy" = { level = "warn", outputs = ["json"] }
```

Define every named output. Levels are `trace`, `debug`, `info`, `warn`, and `error`. Quote logger names containing dots.

Child loggers inherit omitted fields. Explicit lists replace inherited lists; `outputs = []` silences a route. With no root rule, the root uses INFO and every declared output.

## Console output

```toml
[formatters.console]
type = "template"
template = "{timestamp} {level} {logger} {message} {fields}"

[outputs.console]
type = "console"
stream = "stderr"
formatter = "console"
color = { mode = "auto", theme = "ember" }
```

Omit `formatter` to use the default layout. Templates accept `timestamp`, `level`, `logger`, `thread`, `event`, `message`, and `fields`.

Color modes are `auto`, `always`, and `never`. Themes are `ember`, `nord`, and `mono`. Use `exception = { style = "full" }` for full stack traces; the default is compact.

## JSON output

Write JSON lines to stdout:

```toml
[outputs.json]
type = "stream"
stream = "stdout"
```

Or write to a rotating file:

```toml
[outputs.json]
type = "file"
path = "logs/application.jsonl"
append = true
buffer = "64KiB"
flush = "1s"
rotate = { size = "32MiB", keep = 5, compression = "gzip" }
```

Add `"json"` to the root logger's `outputs`.

Files default to a 256 KiB buffer and a one-second flush interval. `flush = "0s"` flushes each record. Add `fsync = true` to force file contents after flushing; it adds storage latency.

Rotation can use `size`, `interval`, or both. It happens between complete records. See [runtime behavior](RUNTIME.md#shutdown-and-files) for ownership and shutdown.

### JSON profiles

Choose `logyard`, `ecs`, or `compact` through an encoder:

```toml
[encoders.application]
type = "json"
profile = "ecs"

[outputs.json]
type = "stream"
encoder = "application"
```

The ECS profile emits ECS 9.4 fields, including `@timestamp`, service details, trace IDs, and errors. Event attributes become labels.

To customize field names, create a profile and set the encoder's `profile` to its name:

```toml
[json_profiles.application]
preset = "logyard"
rename = { body = "message" }
drop = ["message_template"]

[json_profiles.application.attributes]
mode = "flatten"
prefix = "field."
exclude = ["authorization", "cookie"]
```

Attribute modes are `nested`, `flatten`, and `drop`. Attribute selection supports `include`, `exclude`, and `rename`. Keep `timestamp` and at least one of `body` or `event_name`. Custom field changes can affect ECS compatibility.

## Delivery and overflow

Async delivery is the default. Each output gets its own queue and worker:

```toml
[delivery]
mode = "async"
capacity = 256
```

When full, TRACE, DEBUG, and INFO drop immediately. WARN waits up to 2 ms and ERROR up to 20 ms for space, then drops. Drops are counted in runtime health.

For no queue admission waits:

```toml
[delivery.overflow]
warn = "drop"
error = "drop"
```

For another wait duration:

```toml
[delivery.overflow]
error = { action = "wait_drop", timeout = "50ms" }
```

Other actions are `block`, `sync`, and `stderr`. `block` and `stderr` fall back to emergency stderr; `sync` falls back to writing on the caller thread. These writes can block. A rule with no timeout waits zero seconds.

Waits apply per output. Use `mode = "sync"` to write directly on the caller thread.

An output can set `min_level = "error"` and `delivery = { mode = "async", capacity = 1024 }`. This gives ERROR its own queue when the output is also listed on the logger route. Size queues for expected bursts and your heap.

Logging can lose events during overload, output failure, or shutdown. See [health](RUNTIME.md#health) for what to monitor.

## Context and redaction

```toml
[context]
mdc = ["request.id", "tenant.id"]
redact = ["authorization", "cookie", "password", "*.secret", "*.token"]
```

MDC capture is opt-in. Prefer a finite list of keys. [OpenTelemetry](INTEGRATIONS.md#opentelemetry) adds active trace context and selected baggage.

Redaction matches attribute keys or paths, including nested maps. It does not inspect messages, message arguments, exceptions, or resource metadata. Keep secrets out of those values.

## Filters and enrichment

Define a filter and attach its name with `filters = ["sample"]` on a logger rule:

```toml
[filters.sample]
type = "sampling"
probability = 0.25
key = "event-instance"
```

For a per-logger rate limit:

```toml
[filters.noisy]
type = "rate_limit"
permits_per_second = 20
burst = 40
key = "logger"
max_keys = 256
```

Filters apply to every accepted level on their route. See [extensions](EXTENDING.md) for custom filters and enrichers.

## Profiles and overrides

Put environment-specific changes in a profile:

```toml
[profiles.production.loggers]
root = { level = "warn", outputs = ["console"] }
```

Select it with `-Dlogyard.profile=production` or `LOGYARD_PROFILE=production`. Tables merge; arrays and other values replace.

Environment overrides follow the profile, and system properties follow environment overrides:

```sh
export LOGYARD_OVERRIDES='delivery.capacity=512'
java -Dlogyard.override.delivery.capacity=1024 -jar my-app.jar
```

The active profile and overrides are fixed until the runtime is fully closed and started again.

## Reload and shutdown

```toml
[runtime]
watch = true
reload_debounce = "250ms"
shutdown_timeout = "3s"
internal_status = "warn"
```

Watching is off by default and works with filesystem configs. Valid changes replace the routing plan; invalid changes leave the current plan running.

Runtime settings, adapter MDC policy, and file-output settings at an owned path need a handoff or restart. See [reload](RUNTIME.md#reload).

## Validate or migrate a file

From a Zolt application that depends on Logyard:

```sh
zolt build
java -cp "$(zolt classpath runtime)" \
  com.logyard4j.logyard.runtime.tools.LogyardConfigTool validate logyard.toml
```

Use `schema` for all accepted keys, or `explain logyard.toml --logger com.example.Checkout` to inspect a route.

The tool also accepts `migrate-logback logback.xml --output logyard.toml --strict` and `migrate-log4j2 log4j2.xml --output logyard.toml --strict`. Strict mode writes only exact conversions and does not overwrite files. Omit `--strict` for a draft, then review the reported differences.
