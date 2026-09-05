package com.bdemo.system;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RouterTreeBuilderTest {
    @Test
    void buildsLayoutDirectoryAndChildPageForExistingVueRouterContract() {
        List<MenuItem> menus = List.of(
                menu(1, "能源管理", 0, 1, "energy", null, "M"),
                menu(2, "能源总览", 1, 1, "overview", "dashboard/index", "C"));

        List<Map<String, Object>> routers = RouterTreeBuilder.build(menus);

        assertThat(routers).hasSize(1);
        assertThat(routers.get(0)).containsEntry("path", "/energy")
                .containsEntry("component", "Layout")
                .containsEntry("alwaysShow", true);
        assertThat((List<?>) routers.get(0).get("children")).hasSize(1);
    }

    private static MenuItem menu(
            long id, String name, long parentId, int order, String path, String component, String type) {
        return new MenuItem(id, name, parentId, order, path, component, null, null, 1, 0, type, "0", "dashboard");
    }
}
