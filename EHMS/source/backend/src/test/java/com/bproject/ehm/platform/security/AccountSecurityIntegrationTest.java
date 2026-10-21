package com.bproject.ehm.platform.security;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import java.nio.file.*;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:ehm-auth-test;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","ehm.persistence.auto-ddl=true","ehm.auth.mode=local","ehm.auth.cookie-name=EHM_TEST_SESSION","ehm.auth.cookie-secure=false","ehm.audit.iam-required=true","ehm.audit.iam-me-url=http://127.0.0.1:1/unused","ehm.audit.domain=auth-test","ehm.audit.zone=zone-a","ehm.audit.endpoint=","ehm.ai.enabled=false","ehm.config.path=target/no-auth-test-config.json","ehm.cors.allowed-origins=http://localhost:8088"})
@ActiveProfiles("server") @AutoConfigureMockMvc
class AccountSecurityIntegrationTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper mapper;@Autowired JdbcTemplate db;
    static Path bootstrap;
    static final String ADMIN="SyntheticAdminPassword-2026!",USER="SyntheticUserPassword-2026!";
    @DynamicPropertySource static void props(DynamicPropertyRegistry p) throws Exception {
        bootstrap=Files.createTempFile("ehm-auth-test-",".json");
        Files.writeString(bootstrap,"[{\"username\":\"admin\",\"displayName\":\"Test Admin\",\"role\":\"SUPER_ADMIN\",\"password\":\""+ADMIN+"\"},{\"username\":\"viewer\",\"displayName\":\"Test User\",\"role\":\"USER\",\"password\":\""+USER+"\"}]");p.add("ehm.auth.bootstrap-file",()->bootstrap.toString());
    }
    @AfterAll static void cleanup() throws Exception {Files.deleteIfExists(bootstrap);}
    record Session(Cookie cookie,String csrf,String id){}
    Session login(String username,String password) throws Exception {
        var r=mvc.perform(post("/api/ehm/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of("username",username,"password",password))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        String header=r.getResponse().getHeader("Set-Cookie");assertTrue(header.contains("HttpOnly"));assertTrue(header.contains("SameSite=Lax"));
        var json=mapper.readTree(r.getResponse().getContentAsString());assertFalse(json.has("token"));assertFalse(r.getResponse().getContentAsString().contains(password));
        return new Session(new Cookie("EHM_TEST_SESSION",header.substring(header.indexOf('=')+1,header.indexOf(';'))),json.path("csrfToken").asText(),json.path("user").path("id").asText());
    }
    @Test void realLoginTwoRolesCsrfAndRevocationAreEnforced() throws Exception {
        mvc.perform(get("/api/ehm/v1/devices").header("X-Role","SUPER_ADMIN")).andExpect(status().isUnauthorized()).andExpect(header().exists("X-Trace-Id"));
        mvc.perform(post("/api/ehm/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"wrong\"}")).andExpect(status().isUnauthorized());
        Session admin=login("admin",ADMIN),user=login("viewer",USER);
        mvc.perform(get("/api/ehm/v1/auth/me").cookie(user.cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("USER"));
        mvc.perform(get("/api/ehm/v1/devices").cookie(user.cookie())).andExpect(status().isOk());
        String device="{\"code\":\"LOGIN-TEST-01\",\"name\":\"Synthetic login test\"}";
        mvc.perform(post("/api/ehm/v1/devices").cookie(admin.cookie()).contentType(MediaType.APPLICATION_JSON).content(device)).andExpect(status().isForbidden());
        mvc.perform(post("/api/ehm/v1/devices").cookie(user.cookie()).header("X-EHM-CSRF",user.csrf()).header("X-Role","SUPER_ADMIN").contentType(MediaType.APPLICATION_JSON).content(device)).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("一般用户")));
        mvc.perform(post("/api/ehm/v1/devices").cookie(admin.cookie()).header("X-EHM-CSRF",admin.csrf()).contentType(MediaType.APPLICATION_JSON).content(device)).andExpect(status().isCreated());
        mvc.perform(put("/api/ehm/v1/devices/LOGIN-TEST-01").cookie(user.cookie()).header("X-EHM-CSRF",user.csrf()).contentType(MediaType.APPLICATION_JSON).content(device)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/ehm/v1/devices/LOGIN-TEST-01").cookie(user.cookie()).header("X-EHM-CSRF",user.csrf())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/ehm/v1/devices/LOGIN-TEST-01").cookie(admin.cookie()).header("X-EHM-CSRF",admin.csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/ehm/v1/auth/users").cookie(user.cookie())).andExpect(status().isForbidden());
        var list=mvc.perform(get("/api/ehm/v1/auth/users").cookie(admin.cookie())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(list.contains("password"));assertFalse(list.contains("pbkdf2"));
        mvc.perform(put("/api/ehm/v1/auth/users/"+admin.id()).cookie(admin.cookie()).header("X-EHM-CSRF",admin.csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Admin\",\"role\":\"USER\",\"enabled\":false}")).andExpect(status().isConflict());
        mvc.perform(post("/api/ehm/v1/auth/users").cookie(admin.cookie()).header("X-EHM-CSRF",admin.csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"operator\",\"displayName\":\"Operator\",\"role\":\"USER\",\"password\":\"SyntheticOperatorPass-2026!\"}")).andExpect(status().isCreated());
        Session operator=login("operator","SyntheticOperatorPass-2026!");
        mvc.perform(put("/api/ehm/v1/auth/users/"+operator.id()).cookie(admin.cookie()).header("X-EHM-CSRF",admin.csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Operator\",\"role\":\"USER\",\"enabled\":false}")).andExpect(status().isOk());
        mvc.perform(get("/api/ehm/v1/devices").cookie(operator.cookie())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/ehm/v1/auth/password").cookie(user.cookie()).header("X-EHM-CSRF",user.csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\""+USER+"\",\"newPassword\":\"SyntheticChangedPass-2026!\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/ehm/v1/auth/me").cookie(user.cookie())).andExpect(status().isUnauthorized());
        user=login("viewer","SyntheticChangedPass-2026!");
        mvc.perform(post("/api/ehm/v1/auth/logout").cookie(user.cookie()).header("X-EHM-CSRF",user.csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/ehm/v1/devices").cookie(user.cookie())).andExpect(status().isUnauthorized());
        assertTrue(db.queryForList("SELECT password_hash FROM ehm.auth_user").stream().allMatch(row->row.get("password_hash").toString().startsWith("pbkdf2-sha256$600000$")));
        String events=db.queryForList("SELECT payload FROM ehm.audit_outbox").toString();assertTrue(events.contains("LOGIN"));assertTrue(events.contains("LOGOUT"));assertTrue(events.contains("DENIED"));assertFalse(events.contains(ADMIN));assertFalse(events.contains(USER));
    }
    @Test void loginThrottlePersistsAndNeverIssuesCookieForFailure() throws Exception {
        for(int i=0;i<5;i++)mvc.perform(post("/api/ehm/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"nonexistent-test\",\"password\":\"wrong\"}")).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post("/api/ehm/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"nonexistent-test\",\"password\":\"wrong\"}")).andExpect(status().isTooManyRequests());
    }
}
