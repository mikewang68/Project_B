package com.bdemo.admin;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class DeptTreeTest {
    @Test void treeHasLabelsAndStableIdsForElementPlus() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("dept_id",100L,"parent_id",0L,"dept_name","演示组织"),
                Map.of("dept_id",103L,"parent_id",100L,"dept_name","装卸一区")));
        var tree=new AdminService(jdbc,mock(PasswordEncoder.class)).deptTree();
        assertEquals("演示组织",tree.get(0).get("label"));
        assertEquals(100L,tree.get(0).get("id"));
        var child=(Map<?,?>)((List<?>)tree.get(0).get("children")).get(0);
        assertEquals("装卸一区",child.get("label"));
        assertEquals(103L,child.get("id"));
        assertEquals(103L,child.get("deptId"));
    }
}
