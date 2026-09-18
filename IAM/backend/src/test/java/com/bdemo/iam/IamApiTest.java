package com.bdemo.iam;

import com.bdemo.iam.log.mapper.OperationLogMapper;
import com.bdemo.iam.org.mapper.OrgMapper;
import com.bdemo.iam.permission.domain.PermissionNode;
import com.bdemo.iam.permission.mapper.PermissionMapper;
import com.bdemo.iam.role.domain.Role;
import com.bdemo.iam.role.mapper.RoleMapper;
import com.bdemo.iam.user.domain.User;
import com.bdemo.iam.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 无外部依赖的 Web 层测试：Mapper 全部 Mock，不连接 openGauss（Hikari 不会在启动时建连）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IamApiTest {

    /** 离线测试：用 Mock DataSource 顶替 Hikari，使 @Transactional 切面无需真实数据库即可开启/提交事务。 */
    @TestConfiguration
    static class OfflineDataSourceConfig {
        @Bean
        @Primary
        public DataSource dataSource() throws Exception {
            DataSource ds = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);
            when(ds.getConnection()).thenReturn(connection);
            return ds;
        }
    }


    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private UserMapper userMapper;
    @MockBean
    private RoleMapper roleMapper;
    @MockBean
    private PermissionMapper permissionMapper;
    @MockBean
    private OrgMapper orgMapper;
    @MockBean
    private OperationLogMapper operationLogMapper;

    private String adminToken;
    private String viewerToken;

    @BeforeEach
    void setUp() {
        String adminHash = passwordEncoder.encode("Admin@123");
        String viewerHash = passwordEncoder.encode("Viewer@123");
        when(userMapper.selectAuthByUsername("admin")).thenReturn(Map.of(
                "id", "user-admin", "username", "admin",
                "passwordHash", adminHash, "status", "active"));
        when(userMapper.selectAuthByUsername("viewer")).thenReturn(Map.of(
                "id", "user-viewer", "username", "viewer",
                "passwordHash", viewerHash, "status", "active"));
        when(userMapper.selectAuthByUsername("disabled")).thenReturn(Map.of(
                "id", "u-disabled", "username", "disabled",
                "passwordHash", adminHash, "status", "disabled"));

        when(userMapper.selectById("user-admin")).thenReturn(user("user-admin", "admin", "系统管理员"));
        when(userMapper.selectById("user-viewer")).thenReturn(user("user-viewer", "viewer", "观摩用户"));

        when(userMapper.selectRoleIds("user-admin")).thenReturn(List.of("role-super-admin"));
        when(userMapper.selectRoleIds("user-viewer")).thenReturn(List.of("role-viewer"));

        when(roleMapper.selectById("role-super-admin")).thenReturn(role("role-super-admin", "super_admin", "系统管理员"));
        when(roleMapper.selectById("role-viewer")).thenReturn(role("role-viewer", "viewer", "观摩用户"));
        when(roleMapper.selectCodeById("role-super-admin")).thenReturn("super_admin");
        when(roleMapper.selectCodeById("role-viewer")).thenReturn("viewer");

        when(permissionMapper.selectCodesByRoleIds(eq(List.of("role-super-admin"))))
                .thenReturn(List.of("iam:user:list:view", "iam:user:add:add", "iam:role:list:view"));
        when(permissionMapper.selectCodesByRoleIds(eq(List.of("role-viewer"))))
                .thenReturn(List.of("iam:user:list:view"));

        when(permissionMapper.selectAllNodes()).thenReturn(List.of(
                node("sys-iam", null, "SYSTEM", null, "IAM", "iam", 0),
                node("iam-user", "sys-iam", "MENU", null, "用户管理", "iam", 1),
                node("iam:user:list:view", "iam-user", "BUTTON", "iam:user:list:view", "用户管理-查看", "iam", 2)));

        when(userMapper.selectList(anyString(), any(), any(), any())).thenReturn(List.of());

        adminToken = login("admin", "Admin@123");
        viewerToken = login("viewer", "Viewer@123");
    }

    private User user(String id, String username, String name) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setName(name);
        u.setStatus("active");
        return u;
    }

    private Role role(String id, String code, String name) {
        Role r = new Role();
        r.setId(id);
        r.setCode(code);
        r.setName(name);
        r.setStatus("active");
        return r;
    }

    private PermissionNode node(String id, String parent, String type, String code,
                                String name, String system, int sort) {
        PermissionNode n = new PermissionNode();
        n.setId(id);
        n.setParentId(parent);
        n.setNodeType(type);
        n.setCode(code);
        n.setName(name);
        n.setSystem(system);
        n.setSortOrder(sort);
        return n;
    }

    private String login(String username, String password) {
        try {
            MvcResult result = mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode resp = objectMapper.readTree(result.getResponse().getContentAsString());
            return resp.path("data").path("token").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void loginSuccessReturnsToken() {
        org.junit.jupiter.api.Assertions.assertFalse(adminToken.isBlank());
    }

    @Test
    void loginWrongPasswordRejected() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void loginDisabledRejected() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"disabled\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanListUsers() throws Exception {
        mockMvc.perform(get("/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void viewerCannotCreateUser() throws Exception {
        mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"username\":\"x\",\"password\":\"Pass@123\",\"roleIds\":[\"role-viewer\"],\"orgCodes\":[],\"status\":\"active\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateUsernameReturns409() throws Exception {
        when(userMapper.countByUsername("dup")).thenReturn(1);
        mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"重复\",\"username\":\"dup\",\"password\":\"Pass@123\",\"roleIds\":[\"role-viewer\"],\"orgCodes\":[],\"status\":\"active\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void permissionTreeAssembled() throws Exception {
        mockMvc.perform(get("/permissions/tree").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("sys-iam"))
                .andExpect(jsonPath("$.data[0].children[0].id").value("iam-user"))
                .andExpect(jsonPath("$.data[0].children[0].perms.view").value("iam:user:list:view"));
    }

    @Test
    void invalidUsernameSpecialCharsRejected() throws Exception {
        // 用户名含空格/特殊字符必须被服务端拒绝（白名单 ^[A-Za-z0-9_.-]+$），防止注入/越权构造
        mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"非法\",\"username\":\"bad name!\",\"password\":\"Pass@123\","
                                + "\"roleIds\":[\"role-viewer\"],\"orgCodes\":[],\"status\":\"active\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tooShortUsernameRejected() throws Exception {
        mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"短名\",\"username\":\"ab\",\"password\":\"Pass@123\","
                                + "\"roleIds\":[\"role-viewer\"],\"orgCodes\":[],\"status\":\"active\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidUserStatusRejected() throws Exception {
        // 用户状态仅允许 active/disabled
        mockMvc.perform(post("/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"状态非法\",\"username\":\"validuser\",\"password\":\"Pass@123\","
                                + "\"roleIds\":[\"role-viewer\"],\"orgCodes\":[],\"status\":\"hacked\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidRoleStatusRejected() throws Exception {
        // 角色状态仅允许 active/inactive（注意与用户的 active/disabled 不同）
        mockMvc.perform(post("/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"测试角色\",\"code\":\"test_role\",\"description\":\"\","
                                + "\"permCodes\":[],\"status\":\"disabled\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void userListResponseDoesNotLeakPasswordHash() throws Exception {
        // 即使 Mapper 带出了 password，User.password 的 @JsonIgnore 也必须保证响应不含该字段
        User secret = user("user-admin", "admin", "系统管理员");
        secret.setPassword("$2a$10$should-not-be-serialized");
        when(userMapper.selectList(anyString(), any(), any(), any())).thenReturn(List.of(secret));
        mockMvc.perform(get("/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].password").doesNotExist());
    }
}
