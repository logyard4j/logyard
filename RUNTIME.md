# Runtime behavior

[← Logyard](README.md) · [Health](#health-and-diagnostics) · [Reload](#atomic-reload) · [File output](#file-output) · [Event limits](#event-capture)

This guide describes lifecycle and failure behavior. For everyday setup, see [Configuration](CONFIGURATION.md).

## One runtime per process

Native applications, frameworks, and adapters share one runtime identity with reference-counted ownership.

- An early adapter may start the runtime lazily. A later application or framework can reconfigure it without invalidating existing loggers.
- A late adapter cannot overwrite a framework-selected source.
- Closing a `RuntimeBundle` releases that owner's lease. The final owner closes the watcher, delivery, outputs, and process-global `Logyard` reference.
- An adapter lease that borrows a runtime installed with `Logyard.initialize()` leaves configuration and shutdown with the external owner. Closing that lease leaves the runtime installed.
- Independent logging configurations need separate JVMs, including in parallel tests.

Keep a runtime lease for the application's lifetime; see [native setup](INTEGRATIONS.md#native-java).

Output, retirement, configuration-watch, archive-maintenance, and shutdown-hook threads do not inherit the creating thread's `InheritableThreadLocal` values. Capture request metadata into the event; extension callbacks must use that captured data instead of worker-local application context.

Adapters also hold leases. For a coordinated process-wide stop or restart, first stop logging callers, then call `Logyard.shutdown()` to retire every managed lease. Cached facade loggers follow the next installation; see the [lifecycle example](examples/lifecycle).

## Health and diagnostics

With an active `RuntimeBundle logyard`:

```java
var health = logyard.runtime().health();
var route = logyard.runtime().explain("com.example.checkout");
```

| API | Shows |
| --- | --- |
| `health()` | Output failures, worker status, queue capacity/depth, and dropped-event counts |
| `explain(name)` | Effective threshold, level enablement, inherited rule, processors, and outputs |
| `diagnostics_suppressed_total` | Process-wide count of suppressed internal reports |

Async health counters are live, independent snapshots; changing values need not reconcile during traffic.

Aggregate readiness requires every component to be ready. Starting, open-circuit, stopping, stopped, and failed components take precedence over healthy, recovering, or degraded components, regardless of output order.

Use `route.enabled()` to distinguish an operational `OFF` override from the underlying threshold, or `route.isEnabled(Level.DEBUG)` to test a level against the snapshot. The explanation describes routing policy; filtering, output failure, overload, and shutdown can still prevent delivery.

Built-in JSON and console health read last-known state independently of output I/O. A stalled write, flush, or close does not block health collection, including async and Spring Actuator health. The `io_operation` detail (`delegate_io_operation` for async outputs) shows `idle`, `write`, `flush`, or `close`; JSON outputs also report `scheduled_flush`. An operation in progress does not by itself mark the output failed; closing reports `STOPPING` until cleanup finishes.

If a health contributor throws, the runtime and async wrapper report its exception type without invoking message accessors. A failed health snapshot does not disable event delivery.

While runtime shutdown is waiting for publishers or output close calls, its health reports `STOPPING`, `ready = false`, and `pending_retirements`. Reaching the caller's shutdown deadline does not itself change that status to `STOPPED`.

| Metric | Meaning |
| --- | --- |
| `capacity` | Fixed queue slot limit |
| `queued` | Unclaimed events, including offers waiting for a slot; may exceed `capacity` |
| `outstanding_queued_events` | Unclaimed events plus worker-claimed events awaiting completion |
| `active_deliveries` | In-progress delivery calls or batches, including calls waiting on the delegate lock |
| `delivered_total` | Events accepted by the delegate; buffered writes may still fail later |

`queued` is an admission-pressure measure, not an exact count of occupied slots. Waiting offers and queued records are not exposed as separate counters. Successful delivery does not imply flushing or durable storage.

Core publication failures and async delivery/shutdown reports use a shared, rate-limited daemon with at most one bounded message in flight. A stalled stderr cannot block callers on those diagnostic paths or create extra reporters.

Failure summaries bound temporary message assembly before escaping, so oversized exception messages do not require equally large diagnostic buffers.

`runtime.internal_status` controls reload diagnostics. Set it to `off` to suppress those messages.

## Temporary logger levels

Use `LoggerLevelManagement` with the standard runtime to change levels without editing TOML:

```java
import com.logyard4j.logyard.runtime.management.LoggerLevel;
import com.logyard4j.logyard.runtime.management.LoggerLevelManagement;

var levels = LoggerLevelManagement.forRuntime(logyard.runtime());
levels.setLevel("com.example.checkout", LoggerLevel.DEBUG);
levels.setLevel("com.example.noisy", LoggerLevel.OFF);
var checkout = levels.getLoggerLevel("com.example.checkout");
levels.clearLevel("com.example.checkout");
```

Overrides apply to a logger and its descendants. `ROOT` addresses every logger. They survive configuration reload and framework source handoff; clearing an override restores inheritance from the current configuration and remaining overrides.

| API | Returns |
| --- | --- |
| `getLoggerLevel(name)` | Exact base level, exact override, effective level, and its origin |
| `listLoggerLevels()` | A sorted snapshot of configured, overridden, and observed names |
| `listConfiguredLevels()` | Exact temporary overrides |
| `listBaseConfiguredLevels()` | Exact thresholds from the active configuration |

Use `clearAllOverrides()` to restore configuration control everywhere. These operations affect the shared process runtime; previously captured snapshots remain unchanged.

## Atomic reload

Enable [file watching](CONFIGURATION.md#reload-and-shutdown), or request the same reload decision directly:

```java
var result = logyard.reloadNow();
```

Logyard reads a complete candidate, validates and assembles its resources, then publishes the routing plan atomically. A rejected candidate leaves the active plan intact; an unchanged active file is ignored.

### What can reload

| Change | In-place reload |
| --- | --- |
| Logger levels, routes, processors | Supported |
| Add or remove outputs | Supported, subject to resource ownership |
| Replace file-output settings at an already-owned path | Requires process restart |
| `runtime.watch`, `reload_debounce`, `shutdown_timeout`, `internal_status` | Requires application/framework handoff or restart |
| Adapter-owned `context.mdc` capture policy | Requires application/framework handoff or restart |

Changes to a file output's path identity, buffering, rotation, encoder, delivery, or other resource-owning settings at the same owned path are rejected. Logyard never reports a partial policy change as applied.

### Retry behavior

| Failure | Response |
| --- | --- |
| Deterministic candidate error, including parsing, provider validation, path collisions, or restart-required changes | Remember the rejected bytes; retry when the candidate changes |
| Source I/O or temporary resource failure | Retry with bounded exponential backoff |
| Known lifecycle contention | Keep the source dirty until ownership is released |
| Unexpected source-reader runtime failure | Open a circuit until another source-change signal |

Initial installation and framework handoff stabilize the source before commit. If it keeps changing through eight preparation attempts, installation fails explicitly.

Reload observer and diagnostic failures do not change the reload outcome. Recoverable component failures reach emergency stderr; fatal VM failures are rethrown and interrupted status is preserved.

## Output failure and shutdown

Console output detects errors that `PrintStream` normally suppresses. A write, flush, or close failure marks the output failed; later records are rejected without retrying the stream. Failure remains visible after close.

Pretty, template, and emergency output escape control characters, Unicode line separators, and bidirectional controls in logger names. Pretty output accepts arbitrary dotted names and abbreviates without splitting Unicode characters.

JSON stdout/stderr checks the stream when each buffered byte batch is written and on explicit, timed, or shutdown flush. Small records stay buffered until the configured deadline or a flush. Stream failures remain visible after close; neither output closes the process-owned stdout/stderr.

Built-in JSON process streams encode complete records into reusable UTF-8 storage before delivery. Each output retains at most 4 KiB of record storage, with a 768 KiB record ceiling and a separate 8 KiB transport buffer. An encoding rejection leaves the stream healthy and writes no partial record. Custom encoders continue through the public text encoder boundary.

Default asynchronous delivery drops and counts events that arrive after closure or remain queued at the shutdown deadline. See [overflow policies](CONFIGURATION.md#delivery-and-overflow) for caller-thread waiting and fallback behavior.

Delegate delivery and closure share one admission boundary. A synchronous fallback still waiting to enter delivery when close wins is counted as a drop for `drop`/`wait_drop` policies or routed to emergency output for other policies; it is never counted as delivered. Managed publication holds an epoch lease through fallback, so runtime retirement waits for that publisher before closing its output. The shutdown deadline still bounds the caller's wait.

A zero shutdown timeout starts no-wait daemon cleanup. Rollback of an output that never completed initialization still waits for its worker and lease to be released.

## File output

Built-in JSON file output owns a reusable UTF-8 record buffer, capped at 768 KiB and reduced to at most 4 KiB after delivery. This record storage is separate from the configured file buffer. Encoding completes before any record bytes reach the file, and rotation uses the complete record's byte length.

### Opening and ownership

- A candidate reserves exclusive path ownership during assembly. It opens the data file only after commit, when the first record arrives.
- Preparation may create parent directories and a `.logyard.lock` sidecar. Rejected, discarded, failed, or activated-but-unused candidates cannot modify existing data-file contents.
- Flush and close never trigger the first data-file open.
- Two outputs in one candidate or JVM cannot claim the same normalized path or existing hard-link aliases of one file.
- Cross-process hard-link detection depends on the filesystem; pathname sidecar locks do not guarantee it.

### Failure recovery

| Failure | Behavior |
| --- | --- |
| Active write, flush, or rotation-close failure leaves the final record boundary uncertain | Discard buffered bytes, close without retrying them, mark health failed, reject later records |
| Archive move or replacement open fails after the old file closed cleanly | Recovery remains possible |

`fsync = true` calls [`FileChannel.force(false)`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean)) after explicit and scheduled flushes, rotation close, and shutdown close. Force failures make the output fail like flush failures and remain visible after close. The default leaves persistence to the OS; neither mode makes queued events durable or synchronizes archive directory metadata.

`rotate.interval` uses elapsed time since open and checks at record boundaries. On append, it inherits time since the file's last modification. That approximates age across restarts: frequent restarts of a busy file can extend its lifetime. The size limit continues to apply, and idle files rotate on their next record.

## Event capture

Each accepted event carries timestamps, level, logger, message template and arguments, structured attributes, resource and thread metadata, and an optional exception. Outputs share the same immutable event.

Disabled levels do not evaluate suppliers, capture context, read clocks, or capture thread metadata. Java expressions evaluated before the logging call still run; use suppliers for expensive work.

Values are detached at ingress as bounded trees:

| Limit | Maximum |
| --- | --- |
| Nesting | 8 levels |
| Value nodes | 2,048 |
| Aggregate entries | 4,096 |
| Event text | 65,536 UTF-16 characters |
| JSON record | 262,144 characters |
| Complete console event | 131,072 characters |

The event text allowance is partitioned to preserve useful failure information:

| Partition | UTF-16 characters |
| --- | --- |
| Identity | 4,096 |
| Message template | 8,192 |
| Rendered message | 16,384 |
| Exception diagnostics | 16,384 |
| Arguments, attributes, and processor enrichment | 20,480 |

Exception types, messages, and frame fields have reserved allowances. Processor replacements are recaptured under the original budget. Built-in outputs independently enforce depth, identity, entry, and text limits.

### Truncation and identity

| Condition | Representation |
| --- | --- |
| Cycle | `[circular reference]` |
| Repeated container | `[shared reference]` |
| Repeated throwable | `[shared exception reference]` |
| Ingress truncation | `logyard.capture.truncated=true` |
| Lazy message truncation | `LogEvent.renderedMessageTruncated()` |
| Built-in output truncation | `logyard.output.truncated=true` |

Attribute keys are retained whole or omitted. Distinct keys that normalize to the same bounded form are disambiguated, including when attribute sets merge. Application fields and context must avoid the reserved `logyard.*` namespace.

The numeric constants in `CaptureLimits` are stable API: releases may add limits but do not change existing inlined values.

These limits bound retained event data, not arbitrary application `toString()` calls or collection behavior. They also do not bound dynamic logger-name cardinality or total process memory. Reuse stable logger names and size queues, output buffers, resource metadata, and registry overhead together. The capture budgets are fixed in this release.

## JDK message formatting

JUL, `System.Logger`, and Quarkus `MESSAGE_FORMAT` records use bounded `MessageFormat` rendering.

- Exact `Date` values and epoch-millisecond `Long` values use UTC and proleptic-Gregorian semantics.
- Date/time elements accept `short`, `medium`, `long`, and `full` styles.
- Custom `SimpleDateFormat` patterns are rejected; the original template is retained.
- Temporal `printf` conversions use the same UTC and safe-value policy.
