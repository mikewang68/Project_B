package com.bdemo.iam;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bdemo.iam.permission.domain.PermissionNode;
import com.bdemo.iam.role.domain.Role;
import com.bdemo.iam.role.mapper.RoleMapper;
import com.bdemo.iam.user.domain.User;
import com.bdemo.iam.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
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

/**
 * 阶段 2-B 既有 IAM API 兼容性回归测试 (COMP-01)。
 * 确保 /auth/login, /auth/me, /auth/logout, /permissions/tree 四个现有接口的方法、URL 与返回字段语义 100% 保持不变。
 */
@SpringBootTest(
    properties = {
      "iam.isolation-enabled=false",
      "iam.identity.idempotency-secret=offline-idempotency-test-0123456789abcdef",
      "app.jwt.secret=offline-test-only-0123456789abcdef0123456789abcdef",
      "app.sso.ticket-secret=offline-sso-ticket-secret-0123456789abcdef",
      "spring.datasource.url=jdbc:postgresql://127.0.0.1/unused",
      "spring.datasource.username=unused",
      "spring.datasource.password=unused"
    })
@AutoConfigureMockMvc
public class ExistingApiCompatibilityTest {

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

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private PasswordEncoder passwordEncoder;

  @MockBean private UserMapper userMapper;
  @MockBean private RoleMapper roleMapper;
  @MockBean private com.bdemo.iam.permission.mapper.PermissionMapper permissionMapper;
  @MockBean private com.bdemo.iam.security.mapper.SessionMapper sessions;
  @MockBean private com.bdemo.iam.org.mapper.OrgMapper orgMapper;
  @MockBean private com.bdemo.iam.log.mapper.OperationLogMapper operationLogMapper;
  @MockBean private com.bdemo.iam.provisioning.IdentityTasks identities;
  @MockBean private org.springframework.jdbc.core.JdbcTemplate db;

  private String token;

  @BeforeEach
  void setUp() {
    java.util.Map<String, String> activeSessions = new java.util.concurrent.ConcurrentHashMap<>();
    org.mockito.Mockito.doAnswer(call -> {
      activeSessions.put(call.getArgument(0), call.getArgument(1));
      return null;
    }).when(sessions).insert(anyString(), anyString(), any());

    when(sessions.active(anyString(), anyString())).thenAnswer(call ->
        java.util.Objects.equals(activeSessions.get(call.getArgument(0)), call.getArgument(1)) ? 1 : 0);

    org.mockito.Mockito.doAnswer(call -> {
      activeSessions.values().removeIf(user -> user.equals(call.getArgument(0)));
      return null;
    }).when(sessions).revokeUser(anyString());
    org.mockito.Mockito.when(sessions.revokeUserAndReturnIds(anyString())).thenAnswer(call -> {
      var ids = activeSessions.entrySet().stream()
          .filter(entry -> entry.getValue().equals(call.getArgument(0)))
          .map(java.util.Map.Entry::getKey).toList();
      ids.forEach(activeSessions::remove);
      return ids;
    });


    String hash = passwordEncoder.encode("Pass@123");
    when(userMapper.selectAuthByUsername("admin")).thenReturn(Map.of("id", "u-admin", "username", "admin", "passwordHash", hash, "status", "active"));
    User admin = new User();
    admin.setId("u-admin");
    admin.setUsername("admin");
    admin.setName("系统管理员");
    admin.setStatus("active");
    when(userMapper.selectById("u-admin")).thenReturn(admin);
    when(userMapper.selectRoleIds("u-admin")).thenReturn(List.of("r-admin"));

    Role role = new Role();
    role.setId("r-admin");
    role.setCode("super_admin");
    role.setName("系统管理员");
    role.setStatus("active");
    when(roleMapper.selectById("r-admin")).thenReturn(role);
    when(permissionMapper.selectCodesByRoleIds(List.of("r-admin"))).thenReturn(List.of("iam:menu:tree:view"));

    PermissionNode sysIam = new PermissionNode();
    sysIam.setId("sys-iam");
    sysIam.setNodeType("SYSTEM");
    sysIam.setName("IAM");
    sysIam.setSystem("iam");

    PermissionNode menuUser = new PermissionNode();
    menuUser.setId("iam-user");
    menuUser.setParentId("sys-iam");
    menuUser.setNodeType("MENU");
    menuUser.setName("用户管理");
    menuUser.setSystem("iam");

    PermissionNode btnView = new PermissionNode();
    btnView.setId("iam:user:list:view");
    btnView.setParentId("iam-user");
    btnView.setNodeType("BUTTON");
    btnView.setCode("iam:user:list:view");
    btnView.setName("用户列表-查看");
    btnView.setSystem("iam");

    when(permissionMapper.selectAllNodes()).thenReturn(List.of(sysIam, menuUser, btnView));

    try {
      MvcResult res = mockMvc.perform(post("/auth/login")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"username\":\"admin\",\"password\":\"Pass@123\"}"))
          .andExpect(status().isOk())
          .andReturn();
      JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
      token = node.path("data").path("token").asText();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void test_01_auth_login_preserves_envelope_and_token() throws Exception {
    mockMvc.perform(post("/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"admin\",\"password\":\"Pass@123\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.message").value("success"))
        .andExpect(jsonPath("$.data.token").exists())
        .andExpect(jsonPath("$.data.user.username").value("admin"));
  }

  @Test
  void test_02_auth_me_preserves_user_profile() throws Exception {
    mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.user.username").value("admin"))
        .andExpect(jsonPath("$.data.user.name").value("系统管理员"));
  }

  @Test
  void test_03_auth_logout_cleans_session_successfully() throws Exception {
    mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  @Test
  void test_04_permissions_tree_preserves_original_rbac_tree() throws Exception {
    mockMvc.perform(get("/permissions/tree").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data[0].id").value("sys-iam"))
        .andExpect(jsonPath("$.data[0].children[0].id").value("iam-user"))
        .andExpect(jsonPath("$.data[0].children[0].perms.view").value("iam:user:list:view"));
  }
}
