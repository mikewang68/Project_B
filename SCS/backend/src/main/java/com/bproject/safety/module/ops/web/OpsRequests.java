package com.bproject.safety.module.ops.web;

/** 运维 / 云边自治接口请求体（均为可空字段的 Demo 入参）。 */
public final class OpsRequests {

    private OpsRequests() {
    }

    /** POST /ops/simulate：scenario=deviceFault|cacheAlert|timeDrift。 */
    public record SimulateRequest(String scenario, String targetId, String deviceId, String nodeId) {
        public String target() {
            return targetId != null ? targetId : (deviceId != null ? deviceId : nodeId);
        }
    }

    /** POST /ops/edge-nodes/{id}/maintain：action=reconnect|resyncTime|redeliverRule。 */
    public record MaintainRequest(String action) {
    }

    /** POST /edge/simulate-link：state=disconnect|recover。 */
    public record SimulateLinkRequest(String state, String nodeId) {
    }

    /** POST /edge/recover。 */
    public record RecoverRequest(String nodeId) {
    }

    /** POST /edge/reconcile/rules：action=redeliver|keep。 */
    public record ReconcileRulesRequest(String action) {
    }

    /** POST /edge/reconcile/time。 */
    public record ReconcileTimeRequest(String nodeId) {
    }
}
