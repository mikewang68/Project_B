package com.bdemo.scheduling;

/** Existing system-wide algorithms cannot become scoped by setting an actor or region. */
final class EnergyBackgroundPolicy {
    private EnergyBackgroundPolicy() {}
    static void check(String mode,String project,String region,String subject,String session,boolean requestContext) {
        if (!"LEGACY_ONLY".equals(mode) && !"IAM_V2".equals(mode))
            throw new IllegalStateException("BACKGROUND_AUTH_MODE_INVALID");
        if ("IAM_V2".equals(mode)) throw new IllegalStateException("BACKGROUND_V2_NOT_ADAPTED");
        if (project==null || region==null || subject==null || session==null
                || !project.isEmpty() || !region.isEmpty() || !subject.isEmpty() || !session.isEmpty())
            throw new IllegalStateException("BACKGROUND_LEGACY_SCOPE_CONFLICT");
        if (requestContext) throw new IllegalStateException("BACKGROUND_REQUEST_CONTEXT_REJECTED");
    }
}
