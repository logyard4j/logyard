from __future__ import annotations

from pathlib import Path

from .state import CheckState


def check_package_layout(root: Path, state: CheckState) -> None:
    _check_spi_layout(root, state)
    _check_exact_root(
        root,
        "modules/logyard-config-toml/src/main/java/com/zsumz/logyard/config",
        {"ConfigurationException.java", "LogyardConfig.java"},
        "configuration root must contain only the aggregate and shared exception",
        state,
    )
    _check_exact_root(
        root,
        "modules/logyard-config-toml/src/main/java/com/zsumz/logyard/config/loading",
        {"LogyardConfigLoader.java"},
        "configuration loading root must contain only its stable facade",
        state,
    )
    _check_exact_root(
        root,
        "modules/logyard-output-console/src/main/java/com/zsumz/logyard/output/console",
        {"ConsoleSink.java"},
        "console output root must contain only its delivery facade",
        state,
    )
    _check_exact_root(
        root,
        "modules/logyard-runtime/src/main/java/com/zsumz/logyard/runtime/extension/discovery",
        {
            "ContextProviderDiscovery.java", "EventEncoderProviderDiscovery.java", "EventProcessorProviderDiscovery.java",
            "NamedProviderDiscovery.java", "OutputProviderDiscovery.java", "TextFormatterProviderDiscovery.java",
        },
        "extension discovery package is incomplete or misplaced",
        state,
    )
    _check_exact_root(
        root,
        "modules/logyard-core/src/main/java/com/zsumz/logyard/core/delivery",
        {"CompositeSink.java", "FilteringSink.java"},
        "core delivery root must contain only general sink decorators",
        state,
    )
    _check_exact_root(
        root,
        "modules/logyard-core/src/main/java/com/zsumz/logyard/core/runtime",
        {
            "DefaultLogyardRuntime.java", "RuntimeLoggerLevelSnapshot.java", "RuntimeManagementSnapshot.java",
            "RuntimePlan.java", "RuntimePlans.java", "RuntimeReloadDeferredException.java",
        },
        "core runtime root must contain only its facade, plan contracts, and compatibility types",
        state,
    )
    _check_exact_root(
        root,
        "modules/logyard-runtime/src/main/java/com/zsumz/logyard/runtime/reload",
        {"ConfigurationSnapshot.java", "ConfigurationSnapshotReader.java", "WatcherReloadOutcome.java"},
        "runtime reload root must contain only shared snapshots and cross-boundary outcomes",
        state,
    )
    _check_exact_root(
        root,
        "modules/logyard-runtime/src/main/java/com/zsumz/logyard/runtime/installation",
        {
            "ActiveRuntimeConfiguration.java", "ConfigurationInstallationRequest.java", "ConfigurationSnapshotStabilizer.java",
            "ConfigurationWatchHandshake.java", "ConfigurationWatcherCatchUp.java", "ConfigurationWatcherPolicy.java",
            "CurrentWatcherRecovery.java",
            "DeferredWatcherReload.java", "GlobalRuntimeAccess.java", "ManagedRuntimeInstallation.java",
            "ManagedRuntimeReconfiguration.java", "PreparedRuntimeConfiguration.java", "RuntimeConfigurationCleanup.java", "RuntimeInstallation.java",
            "RuntimeInstallationClosure.java", "RuntimeInstallationTransitions.java", "StabilizedConfiguration.java",
        },
        "runtime installation root must contain only configuration lifecycle types and shared ports",
        state,
    )
    _check_exact_root(
        root,
        "modules/logyard-runtime/src/main/java/com/zsumz/logyard/runtime/assembly",
        {"LogyardRuntimeFactory.java", "RuntimeAssembly.java"},
        "runtime assembly root must contain only the construction and candidate facades",
        state,
    )


def _check_spi_layout(root: Path, state: CheckState) -> None:
    spi_root = root / "modules/logyard-api/src/main/java/com/zsumz/logyard/api/spi"
    for source in sorted(spi_root.glob("*.java")):
        if source.name != "package-info.java":
            state.add_error(f"{source.relative_to(root)}: SPI contracts must live in a capability subpackage")


def _check_exact_root(root: Path, relative: str, expected: set[str], message: str, state: CheckState) -> None:
    directory = root / relative
    actual = {source.name for source in directory.glob("*.java") if source.name != "package-info.java"}
    if actual != expected:
        state.add_error(f"{message}; found " + ", ".join(sorted(actual)))
