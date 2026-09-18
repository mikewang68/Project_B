package com.bdemo.sys.dict.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 编辑字典分类：编码不可修改（字典项按 typeCode 关联）。
 */
public class DictTypeUpdateRequest {

    @NotBlank(message = "分类名称不能为空")
    private String name;

    @Pattern(regexp = "active|disabled", message = "状态值非法")
    private String status = "active";

    private String remark;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
