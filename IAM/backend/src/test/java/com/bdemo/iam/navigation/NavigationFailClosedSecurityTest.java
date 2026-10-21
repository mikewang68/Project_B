package com.bdemo.iam.navigation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 阶段 2-C 前置安全补丁测试 (C0)：
 * 验证在缺省配置 (未显式指定 simulate-batch1-approved 开关) 下，系统必须默认 Fail-Closed，
 * 即在无真实数据库批准记录时，绝不从内存静态七页静默放行，返回合法的空系统列表 systems: []。
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
      // 注意：故意不指定 iam.navigation.simulate-batch1-approved，测试其默认值为 false 的安全闭环
    })
@AutoConfigureMockMvc
public class NavigationFailClosedSecurityTest {

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
  @MockBean private com.bdemo.iam.navigation.mapper.NavigationMenuMapper menuMapper;

  private String adminToken;

  @BeforeEach
  void setUp() throws Exception {
    org.mockito.Mockito.when(menuMapper.selectNavEligibleMenus(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Collections.emptyList());
    org.mockito.Mockito.when(menuMapper.selectViewPermissionRules()).thenReturn(java.util.Collections.emptyList());

    java.util.Map<String, String> activeSessions = new java.util.concurrent.ConcurrentHashMap<>();
    org.mockito.Mockito.doAnswer(call -> {
      activeSessions.put(call.getArgument(0), call.getArgument(1));
      return null;
    }).when(sessions).insert(anyString(), anyString(), any());

    when(sessions.active(anyString(), anyString())).thenAnswer(call ->
        java.util.Objects.equals(activeSessions.get(call.getArgument(0)), call.getArgument(1)) ? 1 : 0);

    String hash = passwordEncoder.encode("Pass@123");

    // 管理员账号
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
    when(permissionMapper.selectCodesByRoleIds(List.of("r-admin"))).thenReturn(List.of(
        "iam:user:list:view", "iam:role:list:view", "iam:menu:tree:view",
        "sys:dict:type:view", "sys:log:list:view", "sys:config:list:view"
    ));

    MvcResult res = mockMvc.perform(post("/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"admin\",\"password\":\"Pass@123\"}"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
    adminToken = node.path("data").path("token").asText();
  }

  @Test
  void test_C0_unspecified_switch_defaults_to_false_and_fails_closed() throws Exception {
    // 当没有显式开启 simulate-batch1-approved=true 时，默认值为 false，
    // 即便超级管理员登录，且拥有全部权限，由于真实数据库无获批记录 (0行)，
    // 系统坚决执行 Fail-Closed 策略，返回 systems: [] 空列表，彻底杜绝静默越权放行！
    mockMvc.perform(get("/navigation/tree")
        .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.schemaVersion").value("3.0"))
        .andExpect(jsonPath("$.data.activeBatchId").value("BATCH_1"))
        .andExpect(jsonPath("$.data.userContext.accessMode").value("FULL_ADMIN"))
        .andExpect(jsonPath("$.data.userContext.readOnly").value(false))
        .andExpect(jsonPath("$.data.systems").isArray())
        .andExpect(jsonPath("$.data.systems.length()").value(0));
  }

  @Test
  void test_DB11_database_error_fails_closed_with_50001() throws Exception {
    // 阶段 2-B.2 DB-11 验证：当数据库不可达、连接超时或查询异常时，
    // 坚决 Fail-Closed 返回 HTTP 500，code 50001，data 为 null，禁止任何静默内存兜底！
    org.mockito.Mockito.when(menuMapper.selectNavEligibleMenus(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("DB connection refused"));

    mockMvc.perform(get("/navigation/tree")
        .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value(50001))
        .andExpect(jsonPath("$.data").doesNotExist());
  }
}
