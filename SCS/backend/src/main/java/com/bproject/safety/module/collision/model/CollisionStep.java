package com.bproject.safety.module.collision.model;

/** 防碰撞联动步骤（对齐前端 LinkageStep）。 */
public record CollisionStep(String id, String label, String state, String detail) {

    public CollisionStep with(String state, String detail) {
        return new CollisionStep(id, label, state, detail);
    }
}
