# Configuration

[← Logyard](README.md) · [Outputs](#console-output) · [Delivery](#delivery-and-overflow) · [Context](#context-and-redaction) · [Reload](#reload-and-shutdown)

Logyard uses one TOML file. Define outputs, then choose which loggers send events to them.

## Start small

```toml
schema = 1

[loggers]
root = { level = "info", outputs = ["console"] }

[outputs.console]
type = "console"
```

Save this as `src/main/resources/logyard.toml` or `./logyard.toml`. It writes INFO and above to stderr. The sections below are recipes to merge into that file; replace a matching table instead of declaring it twice.

See [logyard.toml](logyard.toml) for a complete console-and-file example.

## Select a configuration

Logyard uses the first available source in this order:

| Priority | Source |
| --- | --- |
| 1 | `-Dlogyard.config=/path/to/logyard.toml` |
| 2 | `LOGYARD_CONFIG=/path/to/logyard.toml` |
| 3 | Framework-selected source |
| 4 | `classpath:logyard.toml` |
| 5 | `./logyard.toml` |
| 6 | Built-in defaults: INFO, stderr console, async delivery, no watcher |

An explicit source may be a filesystem path or `classpath:logging/logyard-prod.toml`. A missing or invalid explicit source fails startup. Set `-Dlogyard.config.required=true` to require a source when using discovery.

Relative output paths resolve against the configuration file's directory. Classpath and in-memory sources use the base directory supplied by their bootstrap integration.

## TOML basics

| Value | Example |
| --- | --- |
| Environment variable | `name = "${SERVICE_NAME}"` |
| Environment variable with fallback | `name = "${SERVICE_NAME:-checkout}"` |
| Duration | `"250ms"`, `"3s"`, `"2m"`, `"1h"` |
| Size | `"64KiB"`, `"32MiB"`, `"1GiB"` |
| Logger name containing dots | `"com.example.checkout"` |

Use `schema = 1`. Unknown keys, invalid values, and missing component references are rejected.

## Service identity

```toml
[service]
name = "checkout"
version = "${APP_VERSION:-local}"
environment = "${APP_ENVIRONMENT:-development}"

[resource.attributes]
region = "eu-west"
```

Service fields are strings: `name`, `namespace`, `version`, `environment`, and `instance_id`. The defaults are `unknown-service` for the name, an empty namespace, and `unknown` for the remaining fields. Resource attributes are string key/value pairs.

## Levels and routes

```toml
[loggers]
root = { level = "info", outputs = ["console", "json"] }
"com.example" = { level = "debug" }
"com.example.noisy" = { level = "warn", outputs = ["json"] }
```

Define both named outputs before using this route. Quote dotted logger names so TOML treats them as one key.

| Rule field | Meaning |
| --- | --- |
| `level` | `trace`, `debug`, `info`, `warn`, or `error` |
| `outputs` | Named destinations for accepted events |
| `enrich` | Named enrichment processors |
| `filters` | Named filters |

Child loggers inherit omitted fields from their nearest configured parent. Explicit lists replace inherited lists. With no root rule, the root defaults to INFO and all declared outputs.

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

| Option | Default | Choices |
| --- | --- | --- |
| `stream` | `stderr` | `stdout`, `stderr` |
| `formatter` | Built-in console layout | A named formatter |
| `color.mode` | `auto` | `auto`, `always`, `never` |
| `color.theme` | `ember` | `ember`, `nord`, `mono`, or a custom theme |
| `color.capability` | `auto` | `auto`, `ansi16`, `ansi256`, `truecolor` |
| `exception.style` | `compact` | `compact`, `full` |
| `exception.common_frames` | `collapse` | `collapse`, `show` |

Template fields: `{timestamp}`, `{level}`, `{logger}`, `{thread}`, `{event}`, `{message}`, and `{fields}`.

Custom themes use `[themes.NAME]` with styles for `timestamp`, `logger`, `thread`, `event`, `message`, `field_key`, `field_value`, `punctuation`, `exception`, and `stack_frame`. Each style accepts `fg`, `bg`, `bold`, `dim`, `italic`, and `underline`; level styles live under `[themes.NAME.level]`.

## JSON output

Write newline-delimited JSON to stdout:

```toml
[outputs.json]
type = "stream"
stream = "stdout"
```

Or use a rotating file:

```toml
[outputs.json]
type = "file"
path = "logs/application.jsonl"
append = true
buffer = "64KiB"
flush = "1s"
rotate = { size = "32MiB", keep = 5, compression = "gzip" }
```

Add `"json"` to your root logger's `outputs` list.

| Option | File default | Meaning |
| --- | --- | --- |
| `path` | Required | Active file path |
| `append` | `true` | Preserve existing content when opening the file |
| `buffer` | `"256KiB"` | Process buffer; accepts `1KiB`–`16MiB` |
| `flush` | `"1s"` | Flush interval; `"0s"` flushes every record |
| `rotate` | Disabled | Add a table to enable size rotation |
| `rotate.size` | `"1GiB"` when enabled | Rotation threshold |
| `rotate.keep` | `10` when enabled | Archive retention count |
| `rotate.compression` | `none` | `none` or `gzip` |
| `encoder` | Built-in Logyard JSON | A named encoder |

JSON streams default to stdout and `flush = "0s"`; they also accept `encoder` and `flush`.

A positive flush interval starts with the first unflushed record, so sparse traffic also flushes on time. Flushing drains Logyard's buffer; it does not promise `fsync` or durable storage. See [file behavior](RUNTIME.md#file-output) for failure and ownership rules.

### JSON profiles

Select a built-in profile through a named encoder:

```toml
[encoders.application]
type = "json"
profile = "ecs"

[outputs.json]
type = "stream"
encoder = "application"
```

Profiles are `logyard`, `ecs`, and `compact`. To customize one, give the profile a new name and use that name in the encoder's `profile` field:

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

| Transform | Options |
| --- | --- |
| Top-level fields | `rename` and `drop` use canonical field names |
| Attribute placement | `mode = "nested"` (default), `"flatten"`, or `"drop"` |
| Attribute selection | `include`, `exclude`, and `rename` use exact attribute names |
| Flattened attributes | `prefix` defaults to `"attributes."`; must be nonempty |

Canonical fields are `timestamp`, `observed_timestamp_unix_nano`, `severity_number`, `severity_text`, `logger`, `event_name`, `body`, `message_template`, `attributes`, `resource`, `thread`, and `exception`. Keep `timestamp` and at least one of `body` or `event_name`.

## Delivery and overflow

```toml
[delivery]
mode = "async"
capacity = 256
```

Each asynchronous output gets its own queue and worker. A full queue drops new events at every severity by default, including ERROR. Drops are counted in health and summarized by the worker.

| Setting | Default | Meaning |
| --- | --- | --- |
| `mode` | `async` | `async` queues events; `sync` writes on the caller thread |
| `capacity` | `256` | Slots per output; range `16`–`16,777,216` |
| `overflow.LEVEL` | `drop` | Policy for each of `trace`, `debug`, `info`, `warn`, `error` |

Override only the overflow levels you need. This example allows ERROR events to wait briefly for a queue slot:

```toml
[delivery.overflow]
error = { action = "block", timeout = "20ms" }
```

| Action | When the queue stays full |
| --- | --- |
| `drop` | Discard immediately |
| `block` | Wait up to `timeout`, then write the emergency representation to stderr |
| `sync` | Wait up to `timeout`, then deliver on the caller thread |
| `stderr` | Wait up to `timeout`, then write the emergency representation to stderr |

The timeout defaults to zero and bounds queue waiting only. A subsequent output or stderr write may still block. Logging is best effort; use a durable event path for records that must survive overload or process failure.

A full default queue can retain about 32 MiB of event text before object overhead. Size queues for your heap and expected bursts.

Any output can set `min_level` (default `trace`) and override delivery with `delivery = { mode = "async", capacity = 512 }`. Overflow rules remain global. Total asynchronous queue capacity is capped at 16,777,216 slots per configuration.

## Context and redaction

```toml
[context]
mdc = ["request.id", "trace.id"]
redact = ["authorization", "cookie", "password", "*.secret", "*.token"]
```

| Setting | Default | Meaning |
| --- | --- | --- |
| `trace` | `true` | Request trace identity from context providers |
| `mdc` | `[]` | MDC keys to capture |
| `baggage` | `[]` | Baggage keys requested from context providers |
| `redact` | `[]` | Key or path globs to redact recursively before output |

Use a finite MDC allowlist. With Quarkus/JBoss Log Manager, `mdc = ["*"]` copies the entire source MDC for each accepted event.

Redaction matches a map-key leaf or its full path, such as `request.users[0].token`. The `logyard.*` attribute namespace is reserved for system diagnostics.

## Filters and enrichment

Define a filter, then attach its name to a logger:

```toml
[filters.sample]
type = "sampling"
probability = 0.25
key = "event-instance"
seed = 42

[filters.noisy]
type = "rate_limit"
permits_per_second = 20
burst = 40
key = "logger"
max_keys = 256

[loggers]
root = { level = "info", outputs = ["console"] }
"com.example.verbose" = { filters = ["sample"] }
"com.example.noisy" = { filters = ["noisy"] }
```

| Filter | Keys | Defaults |
| --- | --- | --- |
| `sampling` | `event-instance`, `event`, `trace`, `logger`, `attribute:NAME` | Probability `1.0`, key `event-instance`, seed `0` |
| `rate_limit` | `global`, `logger`, `event`, `attribute:NAME` | 100 permits/s, burst 100, key `logger`, max 1,024 keys |

Filters apply to all accepted levels on their route. A sampling key such as `logger` groups decisions by that key; use `event-instance` to sample individual events.

For custom enrichment and filtering, see [Extensions](EXTENDING.md).

## Reload and shutdown

```toml
[runtime]
watch = true
reload_debounce = "250ms"
shutdown_timeout = "3s"
internal_status = "warn"
```

| Setting | Default | Meaning |
| --- | --- | --- |
| `watch` | `false` | Watch a filesystem configuration for changes |
| `reload_debounce` | `"250ms"` | Coalesce changes; accepts `0s`–`30s` |
| `shutdown_timeout` | `"3s"` | Shutdown wait budget; `"0s"` starts no-wait daemon cleanup |
| `internal_status` | `warn` | Reload diagnostics: `off`, `error`, `warn`, `info`, `debug` |

Valid reloads replace the routing plan atomically. Invalid candidates leave the current plan active.

Runtime settings and adapter-owned `context.mdc` policy cannot change through an in-place reload. File output changes at an already-owned path may also require a restart. See the [reload compatibility table](RUNTIME.md#what-can-reload).
