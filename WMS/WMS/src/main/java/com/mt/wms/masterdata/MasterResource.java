package com.mt.wms.masterdata;

enum MasterResource {
    WAREHOUSES, OWNERS, AREAS, WORK_AREAS, LOCATIONS, PARTNERS, CATEGORIES, GOODS, COMPONENTS;

    static MasterResource fromPath(String path) {
        try {
            return valueOf(path.replace('-', '_').toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("不支持的基础资料类型：" + path);
        }
    }
}
