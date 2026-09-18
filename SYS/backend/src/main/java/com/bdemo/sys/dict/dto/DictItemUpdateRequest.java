package com.bdemo.sys.dict.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 编辑字典项：typeCode/value 不可修改（value 参与唯一约束）。
 */
public class DictItemUpdateRequest {

    @NotBlank(message = "字典项标签不能为空")
    private String label;

    private Integer sort = 0;

    @Pattern(regexp = "active|disabled", message = "状态值非法")
    private String status = "active";

    @Pattern(regexp = "primary|success|warning|danger|info|", message = "标签样式非法")
    private String tagType;

    private String remark;

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTagType() {
        return tagType;
    }

    public void setTagType(String tagType) {
        this.tagType = tagType;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
