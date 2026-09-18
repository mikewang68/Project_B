package com.bdemo.iam.permission.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限目录节点（系统 / 菜单分组 / 功能叶子）+ 叶子下的按钮级权限编码。
 * 树结构 JSON 与前端 iam/menu-tree.ts 的 MenuNode 对齐：
 * { id, name, system, path, icon, children, perms: { view: 'xx:view', ... } }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PermissionNode {

    @JsonIgnore
    private String parentId;
    /** SYSTEM / MENU / BUTTON */
    @JsonIgnore
    private String nodeType;
    /** BUTTON 节点的权限编码，唯一 */
    @JsonIgnore
    private String code;
    private String id;
    private String name;
    private String system;
    private String path;
    private String icon;
    @JsonIgnore
    private Integer sortOrder = 0;
    @JsonIgnore
    private String status = "active";

    /** 仅叶子 MENU 节点返回 */
    private Map<String, String> perms;
    private List<PermissionNode> children;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
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

    public String getSystem() {
        return system;
    }

    public void setSystem(String system) {
        this.system = system;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, String> getPerms() {
        return perms;
    }

    public void setPerms(Map<String, String> perms) {
        this.perms = perms;
    }

    public List<PermissionNode> getChildren() {
        return children;
    }

    public void setChildren(List<PermissionNode> children) {
        this.children = children;
    }

    public void addChild(PermissionNode child) {
        if (children == null) {
            children = new ArrayList<>();
        }
        children.add(child);
    }

    public void putPerm(String op, String code) {
        if (perms == null) {
            perms = new LinkedHashMap<>();
        }
        perms.put(op, code);
    }
}
