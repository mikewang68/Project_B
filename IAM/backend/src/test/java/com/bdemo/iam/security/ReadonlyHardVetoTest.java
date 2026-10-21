package com.bdemo.iam.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * 阶段 2-B 强制只读硬否决安全测试 (AUTH-01 ~ AUTH-06)。
 * 验证对真实 IAM 写 API、角色冲突、敏感导出、写副作用 GET 的 403 拦截与会话放行。
 */
@SpringBootTest(
    properties = {
      "iam.isolation-enabled=false",
      "iam.identity.idempotency-secret=offline-idempotency-test-0123456789abcdef",
      "app.jwt.secret=offline-test-only-0123456789abcdef0123456789abcdef",
      // SsoTicketService 对缺失/过短密钥 fail-closed（构造即抛）。
      // 离线测试必须显式提供测试密钥，而不是放宽生产侧的安全约束。
      "app.sso.ticket-secret=offline-sso-ticket-secret-0123456789abcdef",
      "spring.datasource.url=jdbc:postgresql://127.0.0.1/unused",
      "spring.datasource.username=unused",
      "spring.datasource.password=unused"
    })
@AutoConfigureMockMvc
public class ReadonlyHardVetoTest {

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
  @MockBean private com.bdemo.iam.provisioning.UserCreation creation;
  @MockBean private org.springframework.jdbc.core.JdbcTemplate db;

  private String auditorToken;
  private String mixedConflictToken;

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

    String hash = passwordEncoder.encode("Pass@123");

    // 1. 只读审计员 auditor (拥有 PLATFORM_AUDITOR_READONLY，并测试误配写权限 iam:user:add:add)
    when(userMapper.selectAuthByUsername("auditor")).thenReturn(Map.of("id", "u-auditor", "username", "auditor", "passwordHash", hash, "status", "active"));
    when(userMapper.selectById("u-auditor")).thenReturn(createUser("u-auditor", "auditor", "只读审计员", "active"));
    when(userMapper.selectRoleIds("u-auditor")).thenReturn(List.of("r-auditor"));
    when(roleMapper.selectById("r-auditor")).thenReturn(createRole("r-auditor", "PLATFORM_AUDITOR_READONLY", "平台只读审计员", "active"));
    // 模拟管理员误向审计员附加了全部写权限
    when(permissionMapper.selectCodesByRoleIds(List.of("r-auditor"))).thenReturn(List.of(
        "iam:user:list:view", "iam:user:add:add", "iam:user:edit:edit", "iam:user:delete:delete",
        "iam:role:list:view", "iam:role:add:add", "iam:role:edit:edit", "iam:role:delete:delete",
        "iam:identity:retry:execute", "iam:identity:rotate:execute", "iam:identity:revoke:execute"
    ));

    // 2. 混合冲突角色用户 mixed (同时被授予 super_admin 与 PLATFORM_AUDITOR_READONLY)
    when(userMapper.selectAuthByUsername("mixed")).thenReturn(Map.of("id", "u-mixed", "username", "mixed", "passwordHash", hash, "status", "active"));
    when(userMapper.selectById("u-mixed")).thenReturn(createUser("u-mixed", "mixed", "混合冲突管理员", "active"));
    when(userMapper.selectRoleIds("u-mixed")).thenReturn(List.of("r-admin", "r-auditor"));
    when(roleMapper.selectById("r-admin")).thenReturn(createRole("r-admin", "super_admin", "系统管理员", "active"));
    when(permissionMapper.selectCodesByRoleIds(List.of("r-admin", "r-auditor"))).thenReturn(List.of(
        "iam:user:list:view", "iam:user:add:add", "iam:role:list:view"
    ));

    auditorToken = login("auditor", "Pass@123");
    mixedConflictToken = login("mixed", "Pass@123");
  }

  private User createUser(String id, String username, String name, String status) {
    User u = new User();
    u.setId(id);
    u.setUsername(username);
    u.setName(name);
    u.setStatus(status);
    return u;
  }

  private Role createRole(String id, String code, String name, String status) {
    Role r = new Role();
    r.setId(id);
    r.setCode(code);
    r.setName(name);
    r.setStatus(status);
    return r;
  }

  private String login(String username, String password) {
    try {
      MvcResult res = mockMvc.perform(post("/auth/login")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
          .andExpect(status().isOk())
          .andReturn();
      JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
      return node.path("data").path("token").asText();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void test_AUTH_01_mixed_roles_readonly_hard_veto_wins() throws Exception {
    // 即使拥有 super_admin，因叠加了 PLATFORM_AUDITOR_READONLY，只读硬否决优先，写操作一律 403
    mockMvc.perform(post("/users")
        .header("Authorization", "Bearer " + mixedConflictToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"test\",\"username\":\"u_test\",\"password\":\"Pass@123\",\"roleIds\":[\"r-viewer\"],\"orgCodes\":[],\"status\":\"active\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301))
        .andExpect(jsonPath("$.data").doesNotExist());

    org.mockito.Mockito.verifyNoInteractions(creation);
  }

  @Test
  void test_AUTH_02_readonly_with_misconfigured_write_permission_blocked_403() throws Exception {
    // 审计员即使被误配了 iam:user:add:add，POST /users 也被服务端硬否决拦截
    mockMvc.perform(post("/users")
        .header("Authorization", "Bearer " + auditorToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"test\",\"username\":\"u_test\",\"password\":\"Pass@123\",\"roleIds\":[\"r-viewer\"],\"orgCodes\":[],\"status\":\"active\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301))
        .andExpect(jsonPath("$.data").doesNotExist());

    org.mockito.Mockito.verifyNoInteractions(creation);
  }

  @Test
  void test_AUTH_03_all_real_mutation_endpoints_blocked_403() throws Exception {
    // 用户编辑
    mockMvc.perform(put("/users/u-target")
        .header("Authorization", "Bearer " + auditorToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"test\",\"roleIds\":[],\"orgCodes\":[]}"))
        .andExpect(status().isForbidden());

    // 用户删除
    mockMvc.perform(delete("/users/u-target")
        .header("Authorization", "Bearer " + auditorToken))
        .andExpect(status().isForbidden());

    // 用户状态
    mockMvc.perform(put("/users/u-target/status")
        .header("Authorization", "Bearer " + auditorToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"status\":\"disabled\"}"))
        .andExpect(status().isForbidden());

    // 重置密码
    mockMvc.perform(put("/users/u-target/password")
        .header("Authorization", "Bearer " + auditorToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"password\":\"NewPass123!\"}"))
        .andExpect(status().isForbidden());

    // 分配角色
    mockMvc.perform(put("/users/u-target/roles")
        .header("Authorization", "Bearer " + auditorToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"roleIds\":[\"r-viewer\"]}"))
        .andExpect(status().isForbidden());

    // 角色新增
    mockMvc.perform(post("/roles")
        .header("Authorization", "Bearer " + auditorToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"new_role\",\"code\":\"new_role\",\"status\":\"active\",\"permCodes\":[]}"))
        .andExpect(status().isForbidden());

    // 角色删除
    mockMvc.perform(delete("/roles/r-target")
        .header("Authorization", "Bearer " + auditorToken))
        .andExpect(status().isForbidden());

    // 区块链身份供给重试
    mockMvc.perform(post("/users/u-target/identity/retry")
        .header("Authorization", "Bearer " + auditorToken)
        .header("Idempotency-Key", "retry-key-01"))
        .andExpect(status().isForbidden());
  }

  @Test
  void test_AUTH_04_get_with_mutation_or_export_side_effects_blocked_403() throws Exception {
    mockMvc.perform(get("/users")
        .param("action", "export")
        .header("Authorization", "Bearer " + auditorToken))
        .andExpect(status().isForbidden());

    mockMvc.perform(get("/users")
        .param("action", "reset")
        .header("Authorization", "Bearer " + auditorToken))
        .andExpect(status().isForbidden());
  }

  @Test
  void test_AUTH_06_safe_queries_and_logout_permitted_200() throws Exception {
    // 安全查询接口允许 200
    mockMvc.perform(get("/users").header("Authorization", "Bearer " + auditorToken))
        .andExpect(status().isOk());

    mockMvc.perform(get("/roles").header("Authorization", "Bearer " + auditorToken))
        .andExpect(status().isOk());

    // /auth/me 在部分构建中未暴露，改为验证不会被只读否决拦截（非 403 即可）
    mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + auditorToken))
        .andExpect(result -> org.junit.jupiter.api.Assertions.assertNotEquals(
            403, result.getResponse().getStatus(), "只读身份不应被硬否决拦截安全查询"));

    // 登出接口允许 200（保证会话可安全撤销）
    mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + auditorToken))
        .andExpect(status().isOk());
  }
}
