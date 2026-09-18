package com.bdemo.sys;

import com.bdemo.sys.config.AppProperties;
import com.bdemo.sys.config.domain.SysConfigItem;
import com.bdemo.sys.config.mapper.ConfigMapper;
import com.bdemo.sys.dict.domain.DictType;
import com.bdemo.sys.dict.mapper.DictMapper;
import com.bdemo.sys.identity.mapper.IamIdentityMapper;
import com.bdemo.sys.log.domain.SysLog;
import com.bdemo.sys.log.mapper.LogMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 无外部依赖的 Web 层测试：Mapper 全部 Mock（含跨 schema 的 IamIdentityMapper），不连 openGauss。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SysApiTest {

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
    private AppProperties properties;

    @MockBean
    private IamIdentityMapper identityMapper;
    @MockBean
    private DictMapper dictMapper;
    @MockBean
    private LogMapper logMapper;
    @MockBean
    private ConfigMapper configMapper;

    private String adminToken;
    private String viewerToken;

    @BeforeEach
    void setUp() {
        when(identityMapper.selectUser("user-admin")).thenReturn(Map.of(
                "id", "user-admin", "username", "admin", "displayName", "系统管理员", "status", "active"));
        when(identityMapper.selectUser("user-viewer")).thenReturn(Map.of(
                "id", "user-viewer", "username", "viewer", "displayName", "观摩用户", "status", "active"));
        when(identityMapper.selectRoles("user-admin")).thenReturn(List.of(
                Map.of("id", "role-super-admin", "code", "super_admin", "name", "系统管理员")));
        when(identityMapper.selectRoles("user-viewer")).thenReturn(List.of(
                Map.of("id", "role-viewer", "code", "viewer", "name", "观摩用户")));
        when(identityMapper.selectPermissionCodes("user-admin")).thenReturn(List.of("sys:dict:type:view"));
        when(identityMapper.selectPermissionCodes("user-viewer")).thenReturn(List.of(
                "sys:dict:type:view", "sys:dict:item:view",
                "sys:log:list:view", "sys:log:list:export", "sys:config:list:view"));

        DictType type = new DictType();
        type.setId("dt-user_status");
        type.setCode("user_status");
        type.setName("用户状态");
        type.setStatus("active");
        when(dictMapper.selectTypes()).thenReturn(List.of(type));

        SysLog log = new SysLog();
        log.setId("log-1");
        log.setKind("login");
        log.setUsername("admin");
        log.setModule("auth");
        log.setAction("login");
        log.setResult("success");
        log.setCreatedAt(LocalDateTime.now());
        when(logMapper.selectLogs(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(log));

        adminToken = token("user-admin", "admin");
        viewerToken = token("user-viewer", "viewer");
    }

    private String token(String userId, String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 3600_000))
                .signWith(Keys.hmacShaKeyFor(properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void withoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/dict/types")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanListDictTypes() throws Exception {
        mockMvc.perform(get("/dict/types").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("user_status"));
    }

    @Test
    void viewerCannotCreateDictType() throws Exception {
        mockMvc.perform(post("/dict/types")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"x_y\",\"name\":\"测试\",\"status\":\"active\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateDictTypeReturns409() throws Exception {
        when(dictMapper.countTypeByCode("dup_code")).thenReturn(1);
        mockMvc.perform(post("/dict/types")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"dup_code\",\"name\":\"重复\",\"status\":\"active\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void adminCreatesDictType() throws Exception {
        when(dictMapper.countTypeByCode("new_type")).thenReturn(0);
        DictType created = new DictType();
        created.setId("dt-new");
        created.setCode("new_type");
        created.setName("新分类");
        created.setStatus("active");
        when(dictMapper.selectTypeById(anyString())).thenReturn(created);
        mockMvc.perform(post("/dict/types")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"new_type\",\"name\":\"新分类\",\"status\":\"active\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("new_type"));
    }

    @Test
    void viewerCanExportLogsCsv() throws Exception {
        mockMvc.perform(get("/logs/export").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv;charset=UTF-8"));
    }

    @Test
    void viewerCannotEditConfig() throws Exception {
        mockMvc.perform(put("/configs/group/基础设置")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"key\":\"cfg-name\",\"value\":\"x\"}]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminSavesConfigGroup() throws Exception {
        SysConfigItem item = new SysConfigItem();
        item.setId("cfg-name");
        item.setKey("cfg-name");
        item.setLabel("系统名称");
        item.setGroup("基础设置");
        item.setValue("旧值");
        item.setType("string");
        item.setEditable(1);
        when(configMapper.selectAll()).thenReturn(List.of(item));
        when(configMapper.updateValue(eq("cfg-name"), eq("新值"), any())).thenReturn(1);
        mockMvc.perform(put("/configs/group/基础设置")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"key\":\"cfg-name\",\"value\":\"新值\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(1));
    }

    @Test
    void csvExportNeutralizesFormulaInjection() throws Exception {
        // 日志对象/详情若以 = @ 等公式字符开头，导出 CSV 时必须前置单引号，避免 Excel/WPS 公式注入（CWE-1236）
        SysLog evil = new SysLog();
        evil.setId("log-evil");
        evil.setKind("operation");
        evil.setUsername("viewer");
        evil.setModule("user");
        evil.setAction("add");
        evil.setTarget("=1+1");
        evil.setDetail("@SUM(1+1)");
        evil.setResult("success");
        evil.setCreatedAt(LocalDateTime.now());
        when(logMapper.selectLogs(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(evil));
        mockMvc.perform(get("/logs/export").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"'=1+1\"")))
                .andExpect(content().string(containsString("\"'@SUM(1+1)\"")));
    }

    @Test
    void invalidLogDateReturns400() throws Exception {
        // 非法日期参数必须返回 400 而不是被兜底成 500
        mockMvc.perform(get("/logs").param("begin", "not-a-date")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonNumericConfigValueReturns400() throws Exception {
        // number 类型配置写入非数字必须被服务端拒绝（不能只依赖前端控件）
        SysConfigItem num = new SysConfigItem();
        num.setId("cfg-num");
        num.setKey("cfg-num");
        num.setLabel("数值配置");
        num.setGroup("基础设置");
        num.setValue("8");
        num.setType("number");
        num.setEditable(1);
        when(configMapper.selectAll()).thenReturn(List.of(num));
        mockMvc.perform(put("/configs/group/基础设置")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"key\":\"cfg-num\",\"value\":\"abc\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void booleanConfigRejectsInvalidValue() throws Exception {
        // boolean 类型配置只接受 true/false
        SysConfigItem bool = new SysConfigItem();
        bool.setId("cfg-bool");
        bool.setKey("cfg-bool");
        bool.setLabel("开关配置");
        bool.setGroup("基础设置");
        bool.setValue("false");
        bool.setType("boolean");
        bool.setEditable(1);
        when(configMapper.selectAll()).thenReturn(List.of(bool));
        mockMvc.perform(put("/configs/group/基础设置")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entries\":[{\"key\":\"cfg-bool\",\"value\":\"maybe\"}]}"))
                .andExpect(status().isBadRequest());
    }
}
