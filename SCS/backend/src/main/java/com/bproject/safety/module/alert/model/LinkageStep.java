package com.bproject.safety.module.alert.model;

/**
 * 联动执行步骤，字段与前端 LinkageStep 对齐：state ∈ wait/running/success/failed。
 */
public record LinkageStep(String id, String label, String state, String detail, String time) {

    public LinkageStep withState(String newState, String newDetail, String newTime) {
        return new LinkageStep(id, label, newState, newDetail, newTime);
    }

    public static LinkageStep waitStep(String id, String label, String detail) {
        return new LinkageStep(id, label, "wait", detail, null);
    }
}
