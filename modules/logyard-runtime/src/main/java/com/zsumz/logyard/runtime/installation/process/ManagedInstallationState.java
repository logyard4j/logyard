package com.zsumz.logyard.runtime.installation.process;

import com.zsumz.logyard.api.LogyardRuntime;
import com.zsumz.logyard.runtime.installation.RuntimeInstallation;

/** Managed installation identity, configuration authority, and process-global publication state. */
final class ManagedInstallationState {
    private RuntimeInstallation installation;
    private RuntimeOwner configurationAuthority;
    private volatile RuntimeInstallation publishedInstallation;

    boolean isPublishedAs(RuntimeInstallation candidate) {
        return publishedInstallation == candidate;
    }

    RuntimeInstallation installation() {
        return installation;
    }

    boolean isCurrent(RuntimeInstallation candidate) {
        return installation == candidate;
    }

    boolean currentRuntimeMatches(LogyardRuntime runtime) {
        return installation.runtime() == runtime;
    }

    boolean ownerCanReplaceConfiguration(RuntimeOwner owner) {
        return owner.canReplace(configurationAuthority);
    }

    void prepare(RuntimeInstallation candidate, RuntimeOwner owner) {
        installation = candidate;
        configurationAuthority = owner;
    }

    void publish() {
        publishedInstallation = installation;
    }

    void assignConfigurationAuthority(RuntimeOwner owner) {
        configurationAuthority = owner;
    }

    void retainForRetirement(RuntimeInstallation candidate) {
        installation = candidate;
    }

    void withdrawPublication() {
        publishedInstallation = null;
    }

    void clearConfigurationAuthority() {
        configurationAuthority = null;
    }

    void clearInstallation() {
        installation = null;
    }
}
