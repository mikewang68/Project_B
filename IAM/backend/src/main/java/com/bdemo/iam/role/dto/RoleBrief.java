package com.bdemo.iam.role.dto;

/**
 * 角色简要信息（用于有效权限来源展示）。
 */
public class RoleBrief {

    private String id;
    private String code;
    private String name;

    public RoleBrief() {
    }

    public RoleBrief(String id, String code, String name) {
        this.id = id;
        this.code = code;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
