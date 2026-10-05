# Runtime

[Logyard](README.md) · [Configuration](CONFIGURATION.md)

## One runtime per process

Frameworks, adapters, and the Java API share one runtime. Keep a `RuntimeBundle` open for your application's lifetime. Closing it releases that owner's lease; the last owner closes the watcher and outputs.

Adapters can also hold leases. For a full stop or restart, stop logging callers and call `Logyard.shutdown()`. Cached facade loggers follow the next installation. See the [lifecycle example](examples/lifecycle).

Use separate JVMs for tests that need different global logging configurations, or inject a [test kit](INTEGRATIONS.md#testing).

## Health

With an active `RuntimeBundle logyard`:

```java
var health = logyard.runtime().health();
var route = logyard.runtime().explain("com.example.checkout");
```

Check output status, queue pressure, and changes in `dropped_total`. Counters belong to the current output installation, so reset your comparison after an output is replaced.

Built-in health reads last-known state without waiting for output I/O. A stalled write can still be in progress while health is readable.

Logging is best effort. A full queue, failed output, or shutdown deadline can lose events, including ERROR. Use a separate durable, acknowledged path for records that must accompany a business transaction.

## Temporary logger levels

```java
import com.logyard4j.logyard.runtime.management.LoggerLevel;
import com.logyard4j.logyard.runtime.management.LoggerLevelManagement;

var levels = LoggerLevelManagement.forRuntime(logyard.runtime());
levels.setLevel("com.example.checkout", LoggerLevel.DEBUG);
levels.setLevel("com.example.noisy", LoggerLevel.OFF);
levels.clearLevel("com.example.checkout");
```

Overrides apply to descendants and survive file reloads. Use `ROOT` for all loggers, or `clearAllOverrides()` to return to TOML settings.

`explain(name)` shows the effective route. `route.isEnabled(Level.DEBUG)` includes temporary level overrides.

## Reload

Enable file watching in [configuration](CONFIGURATION.md#reload-and-shutdown), or call `logyard.reloadNow()`.

A valid file replaces the routing plan in one step. Invalid changes leave the current plan running. File I/O and temporary resource failures are retried; invalid configuration is retried when the file changes.

| Change | Reload |
| --- | --- |
| Logger levels, routes, and processors | Yes |
| Add or remove an output | Yes, if its resources are available |
| Change a file output at the same owned path | Restart |
| Change runtime settings or adapter MDC policy | Handoff or restart |

## Shutdown and files

Close the runtime during normal application shutdown. It waits up to `runtime.shutdown_timeout` for pending work. Events left queued at the deadline are dropped and counted; cleanup may continue after the wait ends.

File outputs reserve their paths before use and open the data file on the first record. A second output cannot own the same path. A `.logyard.lock` sidecar may remain beside the log file.

Writes, flushes, and rotation failures appear in health. An uncertain record boundary stops further writes rather than retrying buffered records.

`fsync = true` forces file contents after each flush and on close. Queued events can still be lost in a crash. Rotation happens between complete records; interval rotation follows file age rather than calendar boundaries.

## Event capture

Outputs receive the same immutable event. Arguments, fields, context, and exceptions are copied with limits so later caller changes do not alter queued events.

Disabled levels skip capture and suppliers. Ordinary Java argument expressions still run before the logging call; use suppliers for expensive work.

| Limit | Maximum |
| --- | --- |
| Nested values | 8 levels |
| Value nodes | 2,048 |
| Entries | 4,096 |
| Event text | 65,536 UTF-16 characters |

See [CaptureLimits](modules/logyard-api/src/main/java/com/logyard4j/logyard/api/event/CaptureLimits.java) for the full limits. Truncation is marked with `logyard.capture.truncated` or `logyard.output.truncated`. Keep application fields out of the reserved `logyard.*` namespace.

Worker threads use the event's captured context. Dynamic logger names and arbitrary application `toString()` code can still consume resources; reuse stable names.

JUL and `System.Logger` use bounded JDK message formatting. Dates use UTC; custom `SimpleDateFormat` patterns are not supported.
