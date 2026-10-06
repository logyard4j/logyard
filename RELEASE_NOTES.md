# Release notes

[Logyard](README.md)

## 0.1.0-rc.2 — 2026-10-06

- `LogContext.wrap(Executor)` carries the caller's context into each submitted task.
- `AttributeSet.containsKey()` distinguishes a null value from a missing key.
- Route explanations now expose `enabled` and `isEnabled(Level)`. Record patterns for `EffectiveRoute` need the new `enabled` component.
- Spring Boot startup detects competing SLF4J providers.
- Oversized logger names and configuration values are rejected before copying them.

- Output health stays readable while a write, flush, or close is stalled.
- Async writes are serialized with output closure.
- JSON output reuses buffers, and event capture avoids extra copies.

This is a release candidate for Java 21+ JVM applications. Native images, Spring AOT, Quarkus dev and test profiles, and SmallRye readiness are not supported yet.

## Java namespace migration

Java packages changed from `com.logyard4j.*` to `com.logyard4j.logyard.*`. For example, `com.logyard4j.api.event.AttributeSet` is now `com.logyard4j.logyard.api.event.AttributeSet`.

Update imports, reflective class names, service-provider files, and module references, then rebuild your applications and extensions. Earlier compiled code is incompatible.

Maven coordinates still use `com.logyard4j`; dependency names stay the same.
