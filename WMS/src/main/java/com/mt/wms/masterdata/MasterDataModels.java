package com.mt.wms.masterdata;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public final class MasterDataModels {
    private MasterDataModels() {}

    public record Item(Long id, String code, String name, String type, String status,
                       String parentCode, String secondaryCode, String barcode, String specification,
                       String unit, String contact, String telephone, String address, String remark,
                       BigDecimal quantity, BigDecimal price) {}

    public record SaveRequest(@NotBlank(message = "编码不能为空") String code,
                              @NotBlank(message = "名称不能为空") String name,
                              String type, String status, String parentCode, String secondaryCode,
                              String barcode, String specification, String unit, String contact,
                              String telephone, String address, String remark,
                              BigDecimal quantity, BigDecimal price) {}
}
