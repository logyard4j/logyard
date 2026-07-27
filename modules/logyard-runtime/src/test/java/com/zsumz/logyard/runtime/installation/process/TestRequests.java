package com.zsumz.logyard.runtime.installation.process;

import com.zsumz.logyard.runtime.installation.ConfigurationInstallationRequest;

import com.zsumz.logyard.runtime.reload.ConfigurationSnapshot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

final class TestRequests {
    private TestRequests() {
    }

    static ConfigurationInstallationRequest request() {
        String config = "schema = 1\n";
        return new ConfigurationInstallationRequest(
                "test",
                null,
                new Object(),
                () -> ConfigurationSnapshot.capture(
                        "test",
                        Path.of(".").toAbsolutePath(),
                        null,
                        config.getBytes(StandardCharsets.UTF_8)));
    }
}
