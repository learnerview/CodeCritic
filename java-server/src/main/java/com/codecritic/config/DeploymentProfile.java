package com.codecritic.config;

import org.springframework.core.env.Environment;

/**
 * Decides whether the app is running under an explicitly local-development profile, the
 * only context in which the published demo defaults (the {@code admin}/{@code admin}
 * service account and the demo JWT signing secret) are permitted.
 *
 * <p>This is deliberately a default-deny rule: any profile that is not {@code dev} or
 * {@code test}, and a deployment that activates no profile at all, is treated as a real
 * deployment. The previous guard only fired when a {@code prod} profile was explicitly
 * set, so a deployment that simply never set a profile quietly shipped the committed
 * credentials. Making the safe behavior the default means an operator has to opt in to
 * the demo defaults (or supply strong secrets), rather than opting out.
 */
public final class DeploymentProfile {

    private static final String[] DEVELOPMENT_PROFILES = {"dev", "test"};

    private DeploymentProfile() {
    }

    public static boolean allowsDemoDefaults(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            for (String development : DEVELOPMENT_PROFILES) {
                if (development.equalsIgnoreCase(profile)) {
                    return true;
                }
            }
        }
        return false;
    }
}
