package com.bdemo.system;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds the dynamic route structure consumed by the existing RuoYi Vue client. */
public final class RouterTreeBuilder {
    // REQ-057 / REQ-071: visible entries must follow the authenticated role's menu set.
    private RouterTreeBuilder() {}

    public static List<Map<String, Object>> build(List<MenuItem> menus) {
        return childrenOf(0, menus);
    }

    private static List<Map<String, Object>> childrenOf(long parentId, List<MenuItem> menus) {
        return menus.stream()
                .filter(menu -> menu.parentId() == parentId)
                .sorted(Comparator.comparingInt(MenuItem::orderNum).thenComparingLong(MenuItem::menuId))
                .map(menu -> toRouter(menu, menus))
                .toList();
    }

    private static Map<String, Object> toRouter(MenuItem menu, List<MenuItem> menus) {
        Map<String, Object> router = new LinkedHashMap<>();
        router.put("hidden", "1".equals(menu.visible()));
        router.put("name", routeName(menu));
        router.put("path", routePath(menu));
        router.put("component", component(menu));
        if (menu.query() != null && !menu.query().isBlank()) {
            router.put("query", menu.query());
        }
        router.put("meta", Map.of(
                "title", menu.menuName(),
                "icon", menu.icon() == null ? "#" : menu.icon(),
                "noCache", menu.isCache() == 1));
        List<Map<String, Object>> children = childrenOf(menu.menuId(), menus);
        if (!children.isEmpty() && "M".equals(menu.menuType())) {
            router.put("alwaysShow", true);
            router.put("redirect", "noRedirect");
            router.put("children", new ArrayList<>(children));
        }
        return router;
    }

    private static String routeName(MenuItem menu) {
        if (menu.routeName() != null && !menu.routeName().isBlank()) {
            return menu.routeName();
        }
        String path = menu.path() == null ? "route" + menu.menuId() : menu.path();
        StringBuilder result = new StringBuilder();
        for (String part : path.replace('-', '_').split("_")) {
            if (!part.isBlank()) {
                result.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
            }
        }
        return result.toString();
    }

    private static String routePath(MenuItem menu) {
        if (menu.parentId() == 0 && "M".equals(menu.menuType())) {
            return "/" + menu.path();
        }
        return menu.path();
    }

    private static String component(MenuItem menu) {
        if (menu.component() != null && !menu.component().isBlank()) {
            return menu.component();
        }
        return menu.parentId() == 0 ? "Layout" : "ParentView";
    }
}
