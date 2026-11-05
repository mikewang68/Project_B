package com.bdemo.system;

import com.bproject.iam.context.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Test
    void centralMenusIntersectNativePagesAndRetainOnlyTheirDirectories() {
        var menus = List.of(
                menu(2000, "Energy", 0, 1, "energy", null, "M"),
                menu(2001, "Overview", 2000, 1, "overview", "dashboard/index", "C"),
                menu(2004, "Profiles", 2000, 2, "profiles", "energy/profiles", "C"),
                menu(3000, "Administration", 0, 2, "system", null, "M"),
                menu(3001, "Users", 3000, 1, "users", "system/users", "C"));
        var context = context(Map.of("EMCS:2004", Set.of("VIEW"), "EMCS:9999", Set.of("VIEW")));
        var routers = RouterTreeBuilder.build(menus, context);
        assertThat(routers).hasSize(1);
        var children = (List<Map<String, Object>>) routers.get(0).get("children");
        assertThat(children).hasSize(1);
        assertThat(children.get(0)).containsEntry("component", "energy/profiles");
        assertThat(RouterTreeBuilder.build(menus, context(Map.of("EMCS:2004", Set.of("EXPORT"))))).isEmpty();
        assertThat(RouterTreeBuilder.build(menus, (UserContext) null)).hasSize(2);
    }

    private static UserContext context(Map<String, Set<String>> actions) {
        var tuple = new ScopeTriple("PROJECT_B", "SHEN_TIE", "EMCS");
        var filter = ScopeSql.build(ScopeSql.Mode.SCOPED, List.of(tuple));
        return new UserContext(2, "subject", "user", "subject", "user", "L4_OPERATOR", false,
                "EMCS", "PROJECT_B", "SHEN_TIE", "EMCS", ScopeSql.Mode.SCOPED, List.of(tuple),
                List.of(tuple), Set.of("role"), actions.keySet(), actions, Set.of(), "1",
                filter.my_info(), filter.my_project_zone_info(), filter.my_info_template(),
                filter.my_project_zone_info_template(), filter.scope_params());
    }

    private static MenuItem menu(
            long id, String name, long parentId, int order, String path, String component, String type) {
        return new MenuItem(id, name, parentId, order, path, component, null, null, 1, 0, type, "0", "dashboard");
    }
}
