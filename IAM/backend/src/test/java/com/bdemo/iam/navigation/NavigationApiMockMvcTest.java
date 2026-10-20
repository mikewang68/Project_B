package com.bdemo.iam.navigation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bdemo.iam.navigation.enums.AppCodeEnum;
import com.bdemo.iam.navigation.service.NavigationProjectionService;
import com.bdemo.iam.role.domain.Role;
import com.bdemo.iam.role.mapper.RoleMapper;
import com.bdemo.iam.user.domain.User;
import com.bdemo.iam.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * 阶段 2-B 导航 API 契约与安全场景测试 (NAV-01 ~ NAV-10)。
 */
@SpringBootTest(
    properties = {
      "iam.isolation-enabled=false",
      "iam.identity.idempotency-secret=offline-idempotency-test-0123456789abcdef",
      "app.jwt.secret=offline-test-only-0123456789abcdef0123456789abcdef",
      "spring.datasource.url=jdbc:postgresql://127.0.0.1/unused",
      "spring.datasource.username=unused",
      "spring.datasource.password=unused",
      "iam.navigation.simulate-batch1-approved=true"
    })
@AutoConfigureMockMvc
public class NavigationApiMockMvcTest {

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

  private String adminToken;
  private String viewerToken;
  private String wmsToken;
  private String auditorToken;

  @BeforeEach
  void setUp() {
    NavigationProjectionService.clearSimulatedApprovedKeys();

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

    // 1. 全权管理员 admin
    when(userMapper.selectAuthByUsername("admin")).thenReturn(Map.of("id", "u-admin", "username", "admin", "passwordHash", hash, "status", "active"));
    when(userMapper.selectById("u-admin")).thenReturn(createUser("u-admin", "admin", "系统管理员", "active"));
    when(userMapper.selectRoleIds("u-admin")).thenReturn(List.of("r-admin"));
    when(roleMapper.selectById("r-admin")).thenReturn(createRole("r-admin", "super_admin", "系统管理员", "active"));
    when(permissionMapper.selectCodesByRoleIds(List.of("r-admin"))).thenReturn(List.of(
        "iam:user:list:view", "iam:role:list:view", "iam:menu:tree:view", "iam:audit:list",
        "sys:dict:type:view", "sys:log:list:view", "sys:config:list:view"
    ));

    // 2. 普通只读观摩用户 viewer (仅拥有一部分权限)
    when(userMapper.selectAuthByUsername("viewer")).thenReturn(Map.of("id", "u-viewer", "username", "viewer", "passwordHash", hash, "status", "active"));
    when(userMapper.selectById("u-viewer")).thenReturn(createUser("u-viewer", "viewer", "观摩用户", "active"));
    when(userMapper.selectRoleIds("u-viewer")).thenReturn(List.of("r-viewer"));
    when(roleMapper.selectById("r-viewer")).thenReturn(createRole("r-viewer", "viewer", "观摩角色", "active"));
    when(permissionMapper.selectCodesByRoleIds(List.of("r-viewer"))).thenReturn(List.of("iam:user:list:view"));

    // 3. WMS 仓库管理员 wms_mgr (角色 wms_manager)
    when(userMapper.selectAuthByUsername("wms_mgr")).thenReturn(Map.of("id", "u-wms", "username", "wms_mgr", "passwordHash", hash, "status", "active"));
    when(userMapper.selectById("u-wms")).thenReturn(createUser("u-wms", "wms_mgr", "仓库主管", "active"));
    when(userMapper.selectRoleIds("u-wms")).thenReturn(List.of("r-wms"));
    when(roleMapper.selectById("r-wms")).thenReturn(createRole("r-wms", "wms_manager", "仓储管理员", "active"));
    when(permissionMapper.selectCodesByRoleIds(List.of("r-wms"))).thenReturn(List.of("wms:inbound:view", "wms:outbound:view"));

    // 4. 只读审计员 auditor (角色 PLATFORM_AUDITOR_READONLY)
    when(userMapper.selectAuthByUsername("auditor")).thenReturn(Map.of("id", "u-auditor", "username", "auditor", "passwordHash", hash, "status", "active"));
    when(userMapper.selectById("u-auditor")).thenReturn(createUser("u-auditor", "auditor", "只读审计员", "active"));
    when(userMapper.selectRoleIds("u-auditor")).thenReturn(List.of("r-auditor"));
    when(roleMapper.selectById("r-auditor")).thenReturn(createRole("r-auditor", "PLATFORM_AUDITOR_READONLY", "只读审计员", "active"));
    when(permissionMapper.selectCodesByRoleIds(List.of("r-auditor"))).thenReturn(List.of(
        "iam:user:list:view", "iam:role:list:view", "iam:menu:tree:view",
        "sys:dict:type:view", "sys:log:list:view", "sys:config:list:view"
    ));

    // 5. 禁用用户 disabled
    when(userMapper.selectAuthByUsername("disabled")).thenReturn(Map.of("id", "u-dis", "username", "disabled", "passwordHash", hash, "status", "disabled"));
    when(userMapper.selectById("u-dis")).thenReturn(createUser("u-dis", "disabled", "已停用用户", "disabled"));

    adminToken = login("admin", "Pass@123");
    viewerToken = login("viewer", "Pass@123");
    wmsToken = login("wms_mgr", "Pass@123");
    auditorToken = login("auditor", "Pass@123");
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
  void test_NAV_01_anonymous_request_rejected_401() throws Exception {
    mockMvc.perform(get("/navigation/tree"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40101))
        .andExpect(jsonPath("$.message").isNotEmpty())
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void test_NAV_02_disabled_user_rejected_401_or_403() throws Exception {
    mockMvc.perform(post("/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"disabled\",\"password\":\"Pass@123\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void test_NAV_03_wms_manager_query_unauthorized_SYS_returns_empty_systems() throws Exception {
    mockMvc.perform(get("/navigation/tree")
        .param("appCode", "SYS")
        .header("Authorization", "Bearer " + wmsToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.systems").isEmpty())
        .andExpect(jsonPath("$.data.userContext.accessMode").value("SCOPED_USER"));
  }

  @Test
  void test_NAV_04_query_DTS_or_invalid_appCode_rejected_400() throws Exception {
    mockMvc.perform(get("/navigation/tree")
        .param("appCode", "DTS")
        .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001))
        .andExpect(jsonPath("$.data").doesNotExist());

    mockMvc.perform(get("/navigation/tree")
        .param("appCode", "INVALID_APP")
        .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void test_NAV_05_admin_gets_batch1_seven_pages_only() throws Exception {
    mockMvc.perform(get("/navigation/tree")
        .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.schemaVersion").value("3.0"))
        .andExpect(jsonPath("$.data.userContext.accessMode").value("FULL_ADMIN"))
        .andExpect(jsonPath("$.data.userContext.readOnly").value(false))
        // Batch 1 放行 IAM (4) 与 SYS (3)，其余 6 系统未获批，一级节点被剪枝
        .andExpect(jsonPath("$.data.systems.length()").value(2))
        .andExpect(jsonPath("$.data.systems[0].appCode").value("IAM"))
        .andExpect(jsonPath("$.data.systems[0].children.length()").value(4))
        .andExpect(jsonPath("$.data.systems[1].appCode").value("SYS"))
        .andExpect(jsonPath("$.data.systems[1].children.length()").value(3));
  }

  @Test
  void test_NAV_06_two_level_structure_no_categories_no_level3_children() throws Exception {
    MvcResult res = mockMvc.perform(get("/navigation/tree")
        .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andReturn();

    JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
    JsonNode data = root.path("data");

    // 严格禁止出现旧 categories 字段
    org.junit.jupiter.api.Assertions.assertTrue(data.path("categories").isMissingNode());

    JsonNode systems = data.path("systems");
    for (JsonNode sys : systems) {
      org.junit.jupiter.api.Assertions.assertTrue(sys.has("appCode"));
      org.junit.jupiter.api.Assertions.assertTrue(sys.has("sortOrder"));
      org.junit.jupiter.api.Assertions.assertTrue(sys.has("entryBasePath"));
      JsonNode children = sys.path("children");
      org.junit.jupiter.api.Assertions.assertTrue(children.isArray() && children.size() > 0);
      // E21: 支持 APP -> DIRECTORY -> PAGE 三级；目录节点 level=2 可含 children
      for (JsonNode node : children) {
        boolean isDir = "DIRECTORY".equalsIgnoreCase(node.path("nodeType").asText("PAGE"));
        if (isDir) {
          org.junit.jupiter.api.Assertions.assertTrue(node.path("children").isArray()
              && node.path("children").size() > 0, "DIRECTORY 必须有子页");
          for (JsonNode page : node.path("children")) {
            org.junit.jupiter.api.Assertions.assertEquals(3, page.path("level").asInt());
          }
          continue;
        }
        org.junit.jupiter.api.Assertions.assertEquals(2, node.path("level").asInt());
        // E21: PAGE 节点不得含非空 children（空数组 [] 是允许的序列化形态）
        JsonNode kids = node.path("children");
        org.junit.jupiter.api.Assertions.assertTrue(
            kids.isMissingNode() || (kids.isArray() && kids.size() == 0),
            "PAGE 节点不得包含子节点");
      }
    }
  }

  @Test
  void test_NAV_07_entry_mode_invariants_SPA_ROUTE() throws Exception {
    mockMvc.perform(get("/navigation/tree")
        .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.systems[0].children[0].entryMode").value("SPA_ROUTE"))
        .andExpect(jsonPath("$.data.systems[0].children[0].routePath").value("/system/users"))
        .andExpect(jsonPath("$.data.systems[0].children[0].pageKey").value(""))
        .andExpect(jsonPath("$.data.systems[0].children[0].tabKey").value(""));
  }

  @Test
  void test_NAV_08_diagnostics_omitted_for_normal_users_and_present_for_admin() throws Exception {
    // 观摩用户请求带 includeDiagnostics=true：无特权，必须省略
    mockMvc.perform(get("/navigation/tree")
        .param("includeDiagnostics", "true")
        .header("Authorization", "Bearer " + viewerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.diagnostics").doesNotExist());

    // 管理员请求带 includeDiagnostics=true：有特权，返回受控诊断
    mockMvc.perform(get("/navigation/tree")
        .param("includeDiagnostics", "true")
        .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.diagnostics").exists())
        .andExpect(jsonPath("$.data.diagnostics.evaluationMode").value("STRICT_APPROVED_ONLY"))
        .andExpect(jsonPath("$.data.diagnostics.cacheStatus").value("BYPASS_REALTIME"));
  }

  @Test
  void test_NAV_09_readonly_auditor_sees_batch1_with_readonly_flag_and_stripped_ops() throws Exception {
    mockMvc.perform(get("/navigation/tree")
        .header("Authorization", "Bearer " + auditorToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.userContext.accessMode").value("READONLY_AUDITOR"))
        .andExpect(jsonPath("$.data.userContext.readOnly").value(true))
        .andExpect(jsonPath("$.data.systems.length()").value(2))
        // 验证 operationPermissions 仅保留 view/list 权限，无写操作
        .andExpect(jsonPath("$.data.operationPermissions.IAM[0]").value("iam:menu:tree:view"));
  }

  @Test
  void test_NAV_10_logout_revocation_immediately_invalidates_navigation() throws Exception {
    mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());

    mockMvc.perform(get("/navigation/tree").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void test_NAV_11_capture_all_responses_for_schema_validation() throws Exception {
    java.util.Map<String, Object> captures = new java.util.LinkedHashMap<>();

    // 1. NAV-01
    MvcResult r1 = mockMvc.perform(get("/navigation/tree")).andReturn();
    captures.put("NAV-01_anonymous_unauthorized", java.util.Map.of(
        "scenario", "NAV-01: Anonymous request or invalid session token",
        "httpStatus", r1.getResponse().getStatus(),
        "body", objectMapper.readValue(r1.getResponse().getContentAsString(), Object.class)
    ));

    // 2. NAV-02
    MvcResult r2 = mockMvc.perform(get("/navigation/tree").header("Authorization", "Bearer " + adminToken)).andReturn();
    captures.put("NAV-02_superadmin_batch1_full", java.util.Map.of(
        "scenario", "NAV-05 & NAV-06: Super Admin with FULL_ADMIN access mode under Batch 1 release gate",
        "httpStatus", r2.getResponse().getStatus(),
        "body", objectMapper.readValue(r2.getResponse().getContentAsString(), Object.class)
    ));

    // 3. AUTH-01
    MvcResult r3 = mockMvc.perform(get("/navigation/tree").header("Authorization", "Bearer " + auditorToken)).andReturn();
    captures.put("AUTH-01_auditor_readonly_hardveto", java.util.Map.of(
        "scenario", "AUTH-01 & AUTH-05: Platform Auditor with READONLY_AUDITOR access mode",
        "httpStatus", r3.getResponse().getStatus(),
        "body", objectMapper.readValue(r3.getResponse().getContentAsString(), Object.class)
    ));

    // 4. NAV-03
    MvcResult r4 = mockMvc.perform(get("/navigation/tree").param("appCode", "SYS").header("Authorization", "Bearer " + wmsToken)).andReturn();
    captures.put("NAV-03_scoped_wms_user_empty_tree", java.util.Map.of(
        "scenario", "NAV-03: User scoped strictly to WMS system querying SYS",
        "httpStatus", r4.getResponse().getStatus(),
        "body", objectMapper.readValue(r4.getResponse().getContentAsString(), Object.class)
    ));

    // 5. NAV-04
    MvcResult r5 = mockMvc.perform(get("/navigation/tree").param("appCode", "DTS").header("Authorization", "Bearer " + adminToken)).andReturn();
    captures.put("NAV-04_invalid_app_code_400", java.util.Map.of(
        "scenario", "NAV-04: Request with invalid appCode DTS",
        "httpStatus", r5.getResponse().getStatus(),
        "body", objectMapper.readValue(r5.getResponse().getContentAsString(), Object.class)
    ));

    // 6. NAV-08 diagnostics
    MvcResult r6 = mockMvc.perform(get("/navigation/tree").param("includeDiagnostics", "true").header("Authorization", "Bearer " + adminToken)).andReturn();
    captures.put("NAV-08_admin_with_diagnostics_200", java.util.Map.of(
        "scenario", "NAV-08: Authorized admin request with includeDiagnostics=true",
        "httpStatus", r6.getResponse().getStatus(),
        "body", objectMapper.readValue(r6.getResponse().getContentAsString(), Object.class)
    ));

    java.io.File out = new java.io.File("target/nav-contract-captures/http_response_captures.json");
    if (out.getParentFile() != null) { out.getParentFile().mkdirs(); }
    objectMapper.writerWithDefaultPrettyPrinter().writeValue(out, captures);
  }
}
