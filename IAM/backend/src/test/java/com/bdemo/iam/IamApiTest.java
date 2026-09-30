package com.bdemo.iam;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** 无外部依赖的 Web 层测试：Mapper 全部 Mock，不连接 openGauss（Hikari 不会在启动时建连）。 */
@SpringBootTest(
    properties = {
      "iam.isolation-enabled=false",
      "iam.identity.idempotency-secret=offline-idempotency-test-0123456789abcdef",
      "app.jwt.secret=offline-test-only-0123456789abcdef0123456789abcdef",
      "spring.datasource.url=jdbc:postgresql://127.0.0.1/unused",
      "spring.datasource.username=unused",
      "spring.datasource.password=unused"
    })
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

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private PasswordEncoder passwordEncoder;

  @MockBean private UserMapper userMapper;
  @MockBean private RoleMapper roleMapper;
  @MockBean private PermissionMapper permissionMapper;
  @MockBean private OrgMapper orgMapper;
  @MockBean private OperationLogMapper operationLogMapper;

  @MockBean private com.bdemo.iam.security.mapper.SessionMapper sessions;
  @MockBean private com.bdemo.iam.provisioning.IdentityTasks identities;
  @MockBean private org.springframework.jdbc.core.JdbcTemplate db;

  @org.springframework.boot.test.mock.mockito.SpyBean
  private com.bdemo.iam.provisioning.UserCreation creation;

  private String adminToken;
  private String viewerToken;

  @BeforeEach
  void setUp() {
    java.util.Map<String, String> activeSessions = new java.util.concurrent.ConcurrentHashMap<>();
    org.mockito.Mockito.doAnswer(
            call -> {
              activeSessions.put(call.getArgument(0), call.getArgument(1));
              return null;
            })
        .when(sessions)
        .insert(anyString(), anyString(), any());
    when(sessions.active(anyString(), anyString()))
        .thenAnswer(
            call ->
                java.util.Objects.equals(
                        activeSessions.get(call.getArgument(0)), call.getArgument(1))
                    ? 1
                    : 0);
    org.mockito.Mockito.doAnswer(
            call -> {
              activeSessions.values().removeIf(user -> user.equals(call.getArgument(0)));
              return null;
            })
        .when(sessions)
        .revokeUser(anyString());
    when(db.queryForList(anyString(), anyString())).thenReturn(List.of());
    when(identities.status(anyString())).thenReturn(Map.of("state", "NOT_PROVISIONED"));
    String adminHash = passwordEncoder.encode("Admin@123");
    String viewerHash = passwordEncoder.encode("Viewer@123");
    when(userMapper.selectAuthByUsername("admin"))
        .thenReturn(
            Map.of(
                "id",
                "user-admin",
                "username",
                "admin",
                "passwordHash",
                adminHash,
                "status",
                "active"));
    when(userMapper.selectAuthByUsername("viewer"))
        .thenReturn(
            Map.of(
                "id",
                "user-viewer",
                "username",
                "viewer",
                "passwordHash",
                viewerHash,
                "status",
                "active"));
    when(userMapper.selectAuthByUsername("disabled"))
        .thenReturn(
            Map.of(
                "id",
                "u-disabled",
                "username",
                "disabled",
                "passwordHash",
                adminHash,
                "status",
                "disabled"));

    when(userMapper.selectById("user-admin")).thenReturn(user("user-admin", "admin", "系统管理员"));
    when(userMapper.selectById("user-viewer")).thenReturn(user("user-viewer", "viewer", "观摩用户"));

    when(userMapper.selectRoleIds("user-admin")).thenReturn(List.of("role-super-admin"));
    when(userMapper.selectRoleIds("user-viewer")).thenReturn(List.of("role-viewer"));

    when(roleMapper.selectById("role-super-admin"))
        .thenReturn(role("role-super-admin", "super_admin", "系统管理员"));
    when(roleMapper.selectById("role-viewer")).thenReturn(role("role-viewer", "viewer", "观摩用户"));
    when(roleMapper.selectCodeById("role-super-admin")).thenReturn("super_admin");
    when(roleMapper.selectCodeById("role-viewer")).thenReturn("viewer");

    when(permissionMapper.selectCodesByRoleIds(eq(List.of("role-super-admin"))))
        .thenReturn(List.of("iam:user:list:view", "iam:user:add:add", "iam:role:list:view"));
    when(permissionMapper.selectCodesByRoleIds(eq(List.of("role-viewer"))))
        .thenReturn(List.of("iam:user:list:view"));

    when(permissionMapper.selectAllNodes())
        .thenReturn(
            List.of(
                node("sys-iam", null, "SYSTEM", null, "IAM", "iam", 0),
                node("iam-user", "sys-iam", "MENU", null, "用户管理", "iam", 1),
                node(
                    "iam:user:list:view",
                    "iam-user",
                    "BUTTON",
                    "iam:user:list:view",
                    "用户管理-查看",
                    "iam",
                    2)));

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

  private PermissionNode node(
      String id, String parent, String type, String code, String name, String system, int sort) {
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
      MvcResult result =
          mockMvc
              .perform(
                  post("/auth/login")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
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
    mockMvc
        .perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void loginDisabledRejected() throws Exception {
    mockMvc
        .perform(
            post("/auth/login")
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
    mockMvc
        .perform(get("/users").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  @Test
  void viewerCannotCreateUser() throws Exception {
    mockMvc
        .perform(
            post("/users")
                .header("Idempotency-Key", "test-request-key-0001")
                .header("Authorization", "Bearer " + viewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"x\",\"username\":\"x\",\"password\":\"Pass@123\",\"roleIds\":[\"role-viewer\"],\"orgCodes\":[],\"status\":\"active\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void duplicateUsernameReturns409() throws Exception {
    when(userMapper.countByUsername("dup")).thenReturn(1);
    mockMvc
        .perform(
            post("/users")
                .header("Idempotency-Key", "test-request-key-0001")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"重复\",\"username\":\"dup\",\"password\":\"Pass@123\",\"roleIds\":[\"role-viewer\"],\"orgCodes\":[],\"status\":\"active\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void permissionTreeAssembled() throws Exception {
    mockMvc
        .perform(get("/permissions/tree").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value("sys-iam"))
        .andExpect(jsonPath("$.data[0].children[0].id").value("iam-user"))
        .andExpect(jsonPath("$.data[0].children[0].perms.view").value("iam:user:list:view"));
  }

  @Test
  void invalidUsernameSpecialCharsRejected() throws Exception {
    // 用户名含空格/特殊字符必须被服务端拒绝（白名单 ^[A-Za-z0-9_.-]+$），防止注入/越权构造
    mockMvc
        .perform(
            post("/users")
                .header("Idempotency-Key", "test-request-key-0001")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"非法\",\"username\":\"bad name!\",\"password\":\"Pass@123\","
                        + "\"roleIds\":[\"role-viewer\"],\"orgCodes\":[],\"status\":\"active\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void tooShortUsernameRejected() throws Exception {
    mockMvc
        .perform(
            post("/users")
                .header("Idempotency-Key", "test-request-key-0001")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"短名\",\"username\":\"ab\",\"password\":\"Pass@123\","
                        + "\"roleIds\":[\"role-viewer\"],\"orgCodes\":[],\"status\":\"active\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void invalidUserStatusRejected() throws Exception {
    // 用户状态仅允许 active/disabled
    mockMvc
        .perform(
            post("/users")
                .header("Idempotency-Key", "test-request-key-0001")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"状态非法\",\"username\":\"validuser\",\"password\":\"Pass@123\","
                        + "\"roleIds\":[\"role-viewer\"],\"orgCodes\":[],\"status\":\"hacked\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void invalidRoleStatusRejected() throws Exception {
    // 角色状态仅允许 active/inactive（注意与用户的 active/disabled 不同）
    mockMvc
        .perform(
            post("/roles")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"测试角色\",\"code\":\"test_role\",\"description\":\"\","
                        + "\"permCodes\":[],\"status\":\"disabled\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void userListResponseDoesNotLeakPasswordHash() throws Exception {
    // 即使 Mapper 带出了 password，User.password 的 @JsonIgnore 也必须保证响应不含该字段
    User secret = user("user-admin", "admin", "系统管理员");
    secret.setPassword("$2a$10$should-not-be-serialized");
    when(userMapper.selectList(anyString(), any(), any(), any())).thenReturn(List.of(secret));
    mockMvc
        .perform(get("/users").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].password").doesNotExist());
  }

  @Test
  void logoutRevokesTokenAndNextLoginHasANewSession() throws Exception {
    mockMvc
        .perform(post("/auth/logout").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/auth/me").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isUnauthorized());
    String fresh = login("admin", "Admin@123");
    org.junit.jupiter.api.Assertions.assertNotEquals(adminToken, fresh);
    mockMvc
        .perform(get("/auth/me").header("Authorization", "Bearer " + fresh))
        .andExpect(status().isOk());
  }

  @Test
  void disabledUserLosesAuthorizationInExistingSession() throws Exception {
    var disabled = user("user-viewer", "viewer", "观摩用户");
    disabled.setStatus("disabled");
    when(userMapper.selectById("user-viewer")).thenReturn(disabled);
    mockMvc
        .perform(get("/users").header("Authorization", "Bearer " + viewerToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void disabledSuperAdminRoleCannotBypassPermissions() throws Exception {
    var disabled = role("role-super-admin", "super_admin", "系统管理员");
    disabled.setStatus("inactive");
    when(roleMapper.selectById("role-super-admin")).thenReturn(disabled);
    when(permissionMapper.selectCodesByRoleIds(any())).thenReturn(List.of());
    mockMvc
        .perform(get("/users").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isForbidden());
  }

  @Test
  void administratorProtectionIsEnforcedInBackend() throws Exception {
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/users/user-admin")
                .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/roles/role-super-admin/status")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"inactive\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void permissionHealthRequiresAuthenticationAndPermission() throws Exception {
    mockMvc.perform(get("/health/permissions")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/health/permissions").header("Authorization", "Bearer " + viewerToken))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/health/permissions").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());
  }

  @Test
  void disabledUserHasNoEffectivePermissions() throws Exception {
    var disabled = user("user-viewer", "viewer", "观摩用户");
    disabled.setStatus("disabled");
    when(userMapper.selectById("user-viewer")).thenReturn(disabled);
    mockMvc.perform(get("/users/user-viewer/effective-permissions").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false))
        .andExpect(jsonPath("$.data.permissions").isEmpty());
    mockMvc.perform(get("/users/user-viewer/permission-explanation").param("code", "iam:user:list:view")
        .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.owned").value(false));
  }

  @Test
  void superAdminExplanationIncludesImplicitUncataloguedPermission() throws Exception {
    mockMvc.perform(get("/users/user-admin/permission-explanation").param("code", "uncatalogued:operation")
        .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.owned").value(true));
    mockMvc.perform(get("/users/user-admin/effective-permissions").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.superAdmin").value(true));
  }

  @Test
  void inactiveAndDeletedRolesCannotExplainImplicitSuperAdminGrant() throws Exception {
    var inactive = role("role-super-admin", "super_admin", "管理员");
    inactive.setStatus("inactive");
    when(roleMapper.selectById("role-super-admin")).thenReturn(inactive);
    when(permissionMapper.selectCodesByRoleIds(any())).thenReturn(List.of("iam:user:list:view"));
    for (boolean deleted : List.of(false, true)) {
      if (deleted) when(roleMapper.selectById("role-super-admin")).thenReturn(null);
      mockMvc.perform(get("/users/user-admin/permission-explanation").param("code", "uncatalogued:operation")
          .header("Authorization", "Bearer " + viewerToken)).andExpect(status().isOk())
          .andExpect(jsonPath("$.data.owned").value(false));
      mockMvc.perform(get("/health/permissions").header("Authorization", "Bearer " + adminToken))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void ordinaryUserExplanationMatchesEffectiveRoleAndAuthentication() throws Exception {
    var brief = new com.bdemo.iam.role.dto.RoleBrief();
    brief.setId("role-viewer"); brief.setCode("viewer"); brief.setName("观摩员");
    var row = new com.bdemo.iam.role.dto.RolePermissionRow();
    row.setRoleId("role-viewer"); row.setPermissionCode("iam:user:list:view");
    when(roleMapper.selectActiveRolesByUser("user-viewer")).thenReturn(List.of(brief));
    when(roleMapper.selectRolePermissionRows(List.of("role-viewer"))).thenReturn(List.of(row));
    mockMvc.perform(get("/users/user-viewer/permission-explanation").param("code", "iam:user:list:view")
        .header("Authorization", "Bearer " + viewerToken)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.owned").value(true))
        .andExpect(jsonPath("$.data.sourceRoles[0].code").value("viewer"));
    mockMvc.perform(get("/users").header("Authorization", "Bearer " + viewerToken)).andExpect(status().isOk());
    mockMvc.perform(get("/users/user-viewer/permission-explanation").param("code", "uncatalogued:operation")
        .header("Authorization", "Bearer " + viewerToken)).andExpect(status().isOk())
        .andExpect(jsonPath("$.data.owned").value(false));
  }

  @Test
  void createPreservesReady201AndPending202() throws Exception {
    for (String state : List.of("READY", "PENDING")) {
      var created = user("created-user", "new-user", "新增用户");
      created.setFabricIdentity(Map.of("state", state, "taskId", "durable-task"));
      String key = "create-status-" + state;
      org.mockito.Mockito.doReturn(created).when(creation).create(eq(key), any());
      mockMvc.perform(post("/users").header("Authorization", "Bearer " + adminToken)
          .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
          .content("{\"name\":\"新增用户\",\"username\":\"new-user\",\"password\":\"Fixture123!\",\"roleIds\":[\"role-viewer\"],\"orgCodes\":[],\"status\":\"active\"}"))
          .andExpect(status().is("READY".equals(state) ? 201 : 202))
          .andExpect(jsonPath("$.data.fabricIdentity.state").value(state))
          .andExpect(jsonPath("$.data.fabricIdentity.taskId").value("durable-task"));
    }
  }
}
