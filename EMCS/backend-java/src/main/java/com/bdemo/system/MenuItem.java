package com.bdemo.system;

public record MenuItem(
        long menuId,
        String menuName,
        long parentId,
        int orderNum,
        String path,
        String component,
        String query,
        String routeName,
        int isFrame,
        int isCache,
        String menuType,
        String visible,
        String icon) {
}
