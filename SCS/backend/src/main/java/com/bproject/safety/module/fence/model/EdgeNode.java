package com.bproject.safety.module.fence.model;

/** 边缘节点下发状态。 */
public record EdgeNode(String id, String state) {

    public EdgeNode withState(String state) {
        return new EdgeNode(id, state);
    }

    public static final String PENDING = "pending";
    public static final String SYNCING = "syncing";
    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";
}
