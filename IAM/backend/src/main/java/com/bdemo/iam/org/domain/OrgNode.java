package com.bdemo.iam.org.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 组织节点（ZONE/COMPANY/DEPT/GROUP 四级）。JSON 与前端 iam/org-tree.ts 的 OrgNode 对齐。
 */
import com.fasterxml.jackson.annotation.JsonIgnore;

public class OrgNode {

    @JsonIgnore
    private String parentId;
    private String id;
    private String name;
    /** zone / company / dept / group */
    private String type;
    private String code;
    private String path;
    private List<OrgNode> children;

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public List<OrgNode> getChildren() {
        return children;
    }

    public void setChildren(List<OrgNode> children) {
        this.children = children;
    }

    public void addChild(OrgNode child) {
        if (children == null) {
            children = new ArrayList<>();
        }
        children.add(child);
    }
}
