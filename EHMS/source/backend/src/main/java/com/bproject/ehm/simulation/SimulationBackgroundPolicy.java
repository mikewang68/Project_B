package com.bproject.ehm.simulation;

/** Legacy simulation is not a V2 service identity or a regional authorization. */
final class SimulationBackgroundPolicy {
    private SimulationBackgroundPolicy() {}
    static void check(String mode, String region, String subject, String session, boolean requestContext) {
        if (!"LEGACY_ONLY".equals(mode) && !"IAM_V2".equals(mode))
            throw new IllegalStateException("BACKGROUND_AUTH_MODE_INVALID");
        if ("IAM_V2".equals(mode)) throw new IllegalStateException("BACKGROUND_V2_NOT_ADAPTED");
        if (subject == null || session == null || region == null || !subject.isEmpty() || !session.isEmpty()
                || !region.isEmpty() && !"UNASSIGNED".equals(region))
            throw new IllegalStateException("BACKGROUND_LEGACY_SCOPE_CONFLICT");
        if (requestContext) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN,"BACKGROUND_REQUEST_CONTEXT_REJECTED");
    }
}
