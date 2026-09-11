/**
 * Deterministic configuration overlays: named profiles selected at launch, and
 * key-level overrides from system properties and the environment, merged over the
 * parsed TOML document with recorded value origins before strict decoding.
 */
@com.zsumz.logyard.api.annotation.InternalApi
package com.zsumz.logyard.config.loading.overlay;
