from __future__ import annotations

import re
from pathlib import Path

from .state import CheckState


def check_runtime_contracts(root: Path, state: CheckState) -> None:
    runtime_sources = _runtime_sources(root)
    _check_reload_coordination(root, state)
    _check_output_delivery(runtime_sources, state)
    _check_processor_order(runtime_sources, state)
    _check_failure_boundaries(root, state)
    _check_bound_contracts(root, state)
    _check_flush_capacity(root, state)
    _check_adapter_reentry(root, state)
    _check_lifecycle_state_boundaries(root, state)
    _check_test_dependencies(root, state)


def _runtime_sources(root: Path) -> list[tuple[Path, str]]:
    source_root = root / "modules/logyard-runtime/src/main/java"
    return [(source, source.read_text(encoding="utf-8")) for source in sorted(source_root.rglob("*.java"))]


def _check_reload_coordination(root: Path, state: CheckState) -> None:
    coordinator = root / "modules/logyard-runtime/src/main/java/com/logyard4j/runtime/reload/coordination/ReloadCoordinator.java"
    if "synchronized" in coordinator.read_text(encoding="utf-8") or "ReentrantLock" in coordinator.read_text(encoding="utf-8"):
        state.add_error(f"{coordinator.relative_to(root)}: reload coordination must use non-blocking generation ownership")
    reload_state = root / "modules/logyard-runtime/src/main/java/com/logyard4j/runtime/reload/coordination/ReloadState.java"
    state_text = reload_state.read_text(encoding="utf-8")
    for collaborator in ("ConfigurationSnapshotReader", "LogyardRuntimeFactory", "ReloadDiagnostics", "java.io.", "java.nio.file.", "Provider"):
        if collaborator in state_text:
            state.add_error(f"{reload_state.relative_to(root)}: atomic reload state must not invoke or own {collaborator}")
    installation = root / "modules/logyard-runtime/src/main/java/com/logyard4j/runtime/installation/ManagedRuntimeInstallation.java"
    installation_text = installation.read_text(encoding="utf-8")
    if "synchronized" in installation_text or "ReentrantLock" in installation_text:
        state.add_error(f"{installation.relative_to(root)}: managed installation publication must not run under a lock")


def _check_output_delivery(runtime_sources: list[tuple[Path, str]], state: CheckState) -> None:
    pattern = re.compile(r"output\s+instanceof\s+CustomOutputConfig.*?new\s+AsyncSink\(.*?false\s*\)", re.DOTALL)
    if not any(pattern.search(text) for _, text in runtime_sources):
        state.add_error("custom outputs must be wrapped in AsyncSink with caller-thread delivery disabled")


def _check_processor_order(runtime_sources: list[tuple[Path, str]], state: CheckState) -> None:
    declaration = re.compile(r"(?:public|protected|private)?\s+static\s+List<String>\s+processorNames\s*\(")
    methods = [(source, text, match) for source, text in runtime_sources if (match := declaration.search(text)) is not None]
    if len(methods) != 1:
        state.add_error(f"expected one processorNames method, found {len(methods)}")
        return
    source, text, match = methods[0]
    section = _method_section(source, text, match.start(), state)
    order = (
        section.find("result.add(CONTEXT_PROCESSOR)"),
        section.find("result.addAll(safe(rule.filters()))"),
        section.find("result.addAll(safe(rule.enrich()))"),
        section.find("result.add(REDACTION_PROCESSOR)"),
    )
    if any(index < 0 for index in order) or list(order) != sorted(order):
        state.add_error("processor order must remain context, filters, enrichers, redaction")


def _method_section(source: Path, text: str, start: int, state: CheckState) -> str:
    open_brace = text.index("{", start)
    depth = 0
    for index in range(open_brace, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[start:index + 1]
    state.add_error(f"{source}: processorNames method has no balanced closing brace")
    return ""


def _check_failure_boundaries(root: Path, state: CheckState) -> None:
    contracts = {
        "modules/logyard-core/src/main/java/com/logyard4j/core/delivery/async/AsyncDelegateDelivery.java": "ComponentInvocationBoundary.invoke(",
        "modules/logyard-core/src/main/java/com/logyard4j/core/delivery/async/AsyncSinkHealth.java": "ComponentInvocationBoundary.invoke(",
        "modules/logyard-core/src/main/java/com/logyard4j/core/runtime/retirement/RetirementExecutor.java": "ComponentInvocationBoundary.report(",
        "modules/logyard-core/src/main/java/com/logyard4j/core/runtime/management/RuntimeHealthReporter.java": "ComponentInvocationBoundary.invoke(",
        "modules/logyard-core/src/main/java/com/logyard4j/core/runtime/retirement/RuntimeOutputs.java": "ComponentInvocationBoundary.invoke(",
        "modules/logyard-runtime/src/main/java/com/logyard4j/runtime/assembly/output/EventSinkCleanup.java": "ComponentInvocationBoundary.invoke(",
        "modules/logyard-runtime/src/main/java/com/logyard4j/runtime/assembly/output/OutputFactory.java": "ComponentInvocationBoundary.call(",
        "modules/logyard-runtime/src/main/java/com/logyard4j/runtime/assembly/output/EncoderResolver.java": "ComponentInvocationBoundary.call(",
        "modules/logyard-runtime/src/main/java/com/logyard4j/runtime/assembly/output/FormatterResolver.java": "ComponentInvocationBoundary.call(",
        "modules/logyard-runtime/src/main/java/com/logyard4j/runtime/assembly/processing/ProcessorAssembler.java": "ComponentInvocationBoundary.call(",
        "modules/logyard-runtime/src/main/java/com/logyard4j/runtime/extension/ProviderResolver.java": "ComponentInvocationBoundary.call(",
        "modules/logyard-runtime/src/main/java/com/logyard4j/runtime/reload/watcher/ConfigurationWatchLoop.java": "ComponentInvocationBoundary.invoke(",
    }
    for relative, required in contracts.items():
        source = root / relative
        if not source.is_file() or required not in source.read_text(encoding="utf-8"):
            state.add_error(f"{relative}: recoverable SPI and lifecycle callbacks must use the shared component invocation boundary")


def _check_bound_contracts(root: Path, state: CheckState) -> None:
    contracts = {
        "modules/logyard-core/src/main/java/com/logyard4j/core/delivery/CompositeSink.java": ("FailureIsolation.prepareForRecovery(", "ComponentInvocationBoundary.exception("),
        "modules/logyard-api/src/main/java/com/logyard4j/api/format/TextTemplate.java": ("MAX_TEMPLATE_CHARS = 4_096", "MAX_PLACEHOLDERS = 64", "text template must not be blank"),
        "modules/logyard-api/src/main/java/com/logyard4j/api/spi/config/ProviderConfiguration.java": ("MAX_ENTRIES = 64", "MAX_TEXT_CHARS = 4_096", "MAX_LIST_ITEMS = 128"),
        "modules/logyard-runtime/src/main/java/com/logyard4j/runtime/extension/discovery/NamedProviderDiscovery.java": ("MAX_PROVIDERS = 64", "duplicate Logyard"),
        "modules/logyard-api/src/main/java/com/logyard4j/api/spi/encoding/EventEncoderBoundary.java": ("MAX_ENCODED_UTF8_BYTES = 1_048_576", "record.length() > MAX_ENCODED_UTF8_BYTES", "must return exactly one record without line breaks"),
        "modules/logyard-config-toml/src/main/java/com/logyard4j/config/loading/compiler/OutputSectionDecoder.java": ("static final int MAXIMUM_OUTPUTS = 128", "raw.size() > MAXIMUM_OUTPUTS"),
        "modules/logyard-output-json/src/main/java/com/logyard4j/output/json/encoding/JsonEncoder.java": ("public synchronized String encode(LogEvent event)",),
        "modules/logyard-runtime/src/main/java/com/logyard4j/runtime/extension/ExtensionGuardrails.java": ("EventEncoderBoundary.guard(delegate)",),
        "modules/logyard-output-json/src/main/java/com/logyard4j/output/json/flush/SharedFlushScheduler.java": ("new ScheduledThreadPoolExecutor(2", "setRemoveOnCancelPolicy(true)"),
        "modules/logyard-output-json/src/main/java/com/logyard4j/output/json/flush/TimedFlushController.java": ("dispatcher.dispatch(() -> runDispatched(due))",),
        "modules/logyard-output-json/src/main/java/com/logyard4j/output/json/flush/FlushDiagnostics.java": ("private final AtomicReference<ReporterPhase> reporterPhase", "new Thread(null, () -> write(failure), REPORTER_NAME, 0L, false)", "reporter.setDaemon(true)", "reporter.setContextClassLoader(null)"),
        "modules/logyard-output-json/src/main/java/com/logyard4j/output/json/flush/BoundedElasticFlushDispatcher.java": ("static final int MAXIMUM_WORKERS = 128", "static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(2L)", "new BoundedElasticFlushDispatcher(MAXIMUM_WORKERS, DEFAULT_IDLE_TIMEOUT)", "new ThreadPoolExecutor(", "new SynchronousQueue<>()", "new Thread(", "thread.setDaemon(true)", "thread.setContextClassLoader(null)"),
        "modules/logyard-core/src/main/java/com/logyard4j/core/processing/RateLimitProcessor.java": ("maxKeys > 4_096", "buckets.size() >= maxKeys"),
    }
    for relative, fragments in contracts.items():
        text = (root / relative).read_text(encoding="utf-8")
        for fragment in fragments:
            if fragment not in text:
                state.add_error(f"{relative}: missing bound contract {fragment!r}")


def _check_flush_capacity(root: Path, state: CheckState) -> None:
    output_limit = _integer_constant(root, "modules/logyard-config-toml/src/main/java/com/logyard4j/config/loading/compiler/OutputSectionDecoder.java", "MAXIMUM_OUTPUTS", state)
    worker_limit = _integer_constant(root, "modules/logyard-output-json/src/main/java/com/logyard4j/output/json/flush/BoundedElasticFlushDispatcher.java", "MAXIMUM_WORKERS", state)
    if output_limit is not None and worker_limit is not None and worker_limit < output_limit:
        state.add_error("shared JSON flush worker capacity must support every output allowed in one active configuration")
    diagnostics = (root / "modules/logyard-output-json/src/main/java/com/logyard4j/output/json/flush/FlushDiagnostics.java").read_text(encoding="utf-8")
    if "Executor" in diagnostics or "BlockingQueue" in diagnostics:
        state.add_error("timed-flush diagnostics must not retain an executor or diagnostic queue")


def _integer_constant(root: Path, relative: str, name: str, state: CheckState) -> int | None:
    match = re.search(rf"\b{re.escape(name)}\s*=\s*([0-9][0-9_]*)\s*;", (root / relative).read_text(encoding="utf-8"))
    if match is None:
        state.add_error(f"{relative}: missing integer constant {name}")
        return None
    return int(match.group(1).replace("_", ""))


def _check_adapter_reentry(root: Path, state: CheckState) -> None:
    adapters = (
        "modules/logyard-jul/src/main/java/com/logyard4j/jul/LogyardHandler.java",
        "modules/logyard-system-logger/src/main/java/com/logyard4j/systemlogger/internal/factory/LogyardSystemLogger.java",
        "modules/logyard-slf4j2/src/main/java/com/logyard4j/slf4j/internal/logger/LogyardSlf4jLogger.java",
    )
    for relative in adapters:
        text = (root / relative).read_text(encoding="utf-8")
        if "AdapterReentryGuard" not in text or ".enter()" not in text or ".exit()" not in text:
            state.add_error(f"{relative}: adapter ingress must use the shared recursion guard")
    slf4j = (root / adapters[-1]).read_text(encoding="utf-8")
    if "LocationAwareLogger" in slf4j:
        state.add_error("LogyardSlf4jLogger must not implement LocationAwareLogger")
    if "LoggingEventAware" not in slf4j:
        state.add_error("LogyardSlf4jLogger must implement LoggingEventAware")


def _check_lifecycle_state_boundaries(root: Path, state: CheckState) -> None:
    contracts = {
        "modules/logyard-slf4j2/src/main/java/com/logyard4j/slf4j/LogyardServiceProvider.java": ("ProviderState", "Closed.INSTANCE"),
        "modules/logyard-slf4j2/src/main/java/com/logyard4j/slf4j/internal/factory/SwitchableLoggerFactory.java": ("FactoryState", "AwaitingInstallation.INSTANCE"),
        "modules/logyard-runtime/src/main/java/com/logyard4j/runtime/adapter/PublicationGate.java": ("PublicationAdmissionState",),
        "modules/logyard-runtime/src/main/java/com/logyard4j/runtime/installation/process/RuntimeStartTransaction.java": ("ShutdownBoundary",),
        "modules/logyard-runtime/src/main/java/com/logyard4j/runtime/installation/process/RuntimeRetirementTransaction.java": ("ShutdownBoundary",),
        "modules/logyard-runtime/src/main/java/com/logyard4j/runtime/installation/process/RuntimeInstallationState.java": ("ManagedInstallationState", "ProcessShutdownState"),
        "modules/logyard-runtime/src/main/java/com/logyard4j/runtime/diagnostics/AdapterDiagnostics.java": ("DiagnosticRateLimiter",),
        "modules/logyard-slf4j2/src/main/java/com/logyard4j/slf4j/internal/diagnostics/ProviderDiagnostics.java": ("DiagnosticRateLimiter",),
    }
    for relative, required in contracts.items():
        text = (root / relative).read_text(encoding="utf-8")
        for collaborator in required:
            if collaborator not in text:
                state.add_error(f"{relative}: must retain its explicit {collaborator} boundary")


def _check_test_dependencies(root: Path, state: CheckState) -> None:
    for candidate in sorted(root.rglob("*.java")):
        if "target" in candidate.parts:
            continue
        text = candidate.read_text(encoding="utf-8")
        if re.search(r"(?m)^package\s+org\.junit(?:\.|;)", text):
            state.add_error(f"{candidate.relative_to(root)}: checked-in JUnit substitutes are forbidden")
