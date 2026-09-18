package com.bdemo.sys.dict.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class DictTypeRequest {

    @NotBlank(message = "分类编码不能为空")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]{1,63}$", message = "分类编码须以字母开头，仅含字母数字下划线")
    private String code;

    @NotBlank(message = "分类名称不能为空")
    private String name;

    @Pattern(regexp = "active|disabled", message = "状态值非法")
    private String status = "active";

    private String remark;

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
