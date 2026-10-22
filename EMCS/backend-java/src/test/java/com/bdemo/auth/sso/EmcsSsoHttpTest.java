package com.bdemo.auth.sso;

import com.bdemo.auth.JwtService;
import com.bdemo.auth.CaptchaService;
import com.bdemo.energy.overview.OverviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real EMCS MVC/Security/MyBatis/transactions + real HTTP IAM stub. This is not live IAM or openGauss E2E. */
@SpringBootTest(properties={"b-demo.auth.captcha-enabled=true","b-demo.scheduling-enabled=false",
        "emcs.sso.enabled=true","emcs.sso.allow-insecure-http=true","emcs.sso.timeout-ms=500"})
@AutoConfigureMockMvc
class EmcsSsoHttpTest {
    static final String SUBJECT="11111111-1111-4111-8111-111111111111";
    static final String OTHER="22222222-2222-4222-8222-222222222222";
    static final String SERVICE="test-only-service-identity-32-characters";
    static final ObjectMapper JSON=new ObjectMapper();
    static final SsoTestDatabase DB;
    static final HttpServer IAM;
    static final Set<String> CONSUMED=ConcurrentHashMap.newKeySet();
    static final AtomicInteger CALLS=new AtomicInteger();
    static volatile Map<String,Object> assertion;
    static volatile int iamStatus=200;
    static volatile boolean invalidBusinessCode=false;
    static {
        try {
            DB=new SsoTestDatabase();
            IAM=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            IAM.createContext("/auth/sso/verify-and-consume",exchange->{
                CALLS.incrementAndGet();
                int status=iamStatus;Map<String,Object> out;
                var body=JSON.readTree(exchange.getRequestBody());
                String ticket=body.path("ticket").asText();
                if(!SERVICE.equals(exchange.getRequestHeaders().getFirst("X-SSO-Service-Token")) || !"emcs".equals(body.path("audience").asText()))status=403;
                if(ticket.equals("expired") || !CONSUMED.add(ticket))status=401;
                out=status==200?Map.of("code",invalidBusinessCode?1:0,"data",assertion):Map.of("code",40101);
                byte[] bytes=JSON.writeValueAsBytes(out);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);
                exchange.getResponseBody().write(bytes);exchange.close();
            });
            IAM.start();
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",DB.source::getUrl);r.add("spring.datasource.username",()->"sa");r.add("spring.datasource.password",()->"");
        r.add("spring.datasource.driver-class-name",()->"org.h2.Driver");r.add("spring.datasource.hikari.connection-init-sql",()->"SELECT 1");
        r.add("emcs.sso.iam-base-url",()->"http://127.0.0.1:"+IAM.getAddress().getPort());r.add("emcs.sso.service-token",()->SERVICE);
    }
    @Autowired MockMvc http;
    @Autowired JwtService jwt;
    @Autowired EmcsSsoSessionRepository sessions;
    @Autowired EmcsSsoProperties properties;
    @MockBean CaptchaService captcha;
    @MockBean OverviewService overview;

    @BeforeEach void setup(){
        for(String table:new String[]{"emcs_sso_session","emcs_sso_iam_session","emcs_sso_subject_map","sys_user_role","sys_role_menu","sys_menu","sys_role","sys_user","e_area"})DB.jdbc.update("DELETE FROM "+table);
        DB.jdbc.update("INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,password,status,del_flag) VALUES(42,1,'operator','Operator',?,'0','0')",new BCryptPasswordEncoder().encode("local-test-password"));
        DB.jdbc.update("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status) VALUES(10,'Manager','energy_mgr',1,'0')");
        DB.jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(42,10)");
        DB.jdbc.update("INSERT INTO e_area(area_id,area_code,area_name,cargo_type) VALUES(1,'AREA-A','A','test'),(2,'AREA-B','B','test')");
        DB.jdbc.update("INSERT INTO emcs_sso_subject_map(iam_subject,local_user_id,scope_area,iam_domain,iam_zone) VALUES(?,42,'*','test-domain','ALL')",SUBJECT);
        DB.jdbc.update("INSERT INTO sys_menu(menu_id,menu_name,parent_id,path,menu_type,status,visible) VALUES(2000,'Energy',0,'energy','M','0','0'),(2001,'Overview',2000,'overview','C','0','0'),(100,'Users',0,'system/user','C','0','0'),(2003,'Hidden',2000,'statistics','C','0','1')");
        DB.jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(10,2000),(10,2001),(10,100),(10,2003)");
        DB.jdbc.update("UPDATE sys_menu SET perms='energy:overview:view' WHERE menu_id=2001");
        CONSUMED.clear();CALLS.set(0);iamStatus=200;invalidBusinessCode=false;
        assertion=new HashMap<>(Map.of("subject",SUBJECT,"sessionId","iam-session-1","audience","[emcs]","localSubject","operator","contractVersion","1.1","company","","org","","domain","test-domain","zone","ALL"));
        properties.setEnabled(true);properties.setAllowInsecureHttp(true);properties.setIamBaseUrl("http://127.0.0.1:"+IAM.getAddress().getPort());
        when(overview.summary(anyString(),anyString(),anyString())).thenAnswer(inv->Map.of("zone",inv.getArgument(1)));
    }
    @AfterAll static void stop(){IAM.stop(0);}
    MvcResult exchange(String ticket,int expected)throws Exception{
        return http.perform(post("/api/auth/sso/exchange").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("ticket",ticket))))
                .andExpect(status().is(expected)).andReturn();
    }
    String token(String ticket)throws Exception{
        var result=exchange(ticket,200);
        assertNull(result.getRequest().getSession(false),"SSO must not rely on HttpSession in a stateless JWT application");
        assertEquals("no-store",result.getResponse().getHeader("Cache-Control"));
        return JSON.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    @Test void exchangeAuthenticatesLaterRequestAndFiltersMenus()throws Exception{
        String token=token("valid");assertTrue(token.startsWith("emcs_sso_"));
        http.perform(get("/getInfo").header("Authorization","Bearer "+token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.userId").value(42)).andExpect(jsonPath("$.sso.readOnly").value(true));
        String routes=http.perform(get("/getRouters").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(routes.contains("overview"));assertFalse(routes.contains("system/user"));assertFalse(routes.contains("statistics"));
        assertFalse(DB.jdbc.queryForObject("SELECT token_hash FROM emcs_sso_session",String.class).contains(token));
    }
    @Test void expiredReplayedWrongAudienceAndBadContractAreRejected()throws Exception{
        exchange("expired",401);String t=token("once");exchange("once",401);
        assertion.put("audience","[wms]");exchange("wrong-aud",401);
        assertion.put("audience","[emcs]");assertion.put("contractVersion","1.0");exchange("bad-version",401);
        assertEquals(1,DB.jdbc.queryForObject("SELECT COUNT(*) FROM emcs_sso_session",Integer.class));
        assertTrue(sessions.findActive(t).isPresent());
    }
    @Test void unboundDisabledDeletedAndMismatchNeverFallback()throws Exception{
        assertion.put("subject",OTHER);exchange("unbound",403);
        assertion.put("subject",SUBJECT);DB.jdbc.update("UPDATE emcs_sso_subject_map SET enabled=0");exchange("disabled-map",403);
        DB.jdbc.update("UPDATE emcs_sso_subject_map SET enabled=1");DB.jdbc.update("UPDATE sys_user SET status='1'");exchange("disabled-user",403);
        DB.jdbc.update("UPDATE sys_user SET status='0',del_flag='2'");exchange("deleted-user",403);
        DB.jdbc.update("UPDATE sys_user SET del_flag='0'");assertion.put("localSubject","admin");exchange("wrong-local",403);
        assertion.put("localSubject","operator");assertion.put("domain","different");exchange("wrong-domain",403);
        assertEquals(0,DB.jdbc.queryForObject("SELECT COUNT(*) FROM emcs_sso_session",Integer.class));
    }
    @Test void emptyScopeMissingSessionAndMalformedResponsesFailClosed()throws Exception{
        DB.jdbc.update("UPDATE emcs_sso_subject_map SET scope_area=''");exchange("empty-scope",403);
        DB.jdbc.update("UPDATE emcs_sso_subject_map SET scope_area='*'");assertion.remove("sessionId");exchange("missing-sid",401);
        assertion.put("sessionId","iam-session-1");invalidBusinessCode=true;exchange("bad-business",401);
        invalidBusinessCode=false;iamStatus=503;exchange("unavailable",503);
    }
    @Test void readonlyDeniesWritesAndSensitiveGetExportsRegardlessOfClaimedHeaders()throws Exception{
        String t=token("read-only");
        http.perform(post("/raw-quality/recompute").header("Authorization","Bearer "+t).header("readOnly","false").header("superAdmin","true").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        http.perform(get("/reports/archives/1/export").header("Authorization","Bearer "+t)).andExpect(status().isForbidden());
        http.perform(delete("/monitor/cache/clearCacheAll").header("Authorization","Bearer "+t)).andExpect(status().isForbidden());
    }
    @Test void restrictedAreaChecksQueryAndKeepsUnscopedApisClosed()throws Exception{
        DB.jdbc.update("UPDATE emcs_sso_subject_map SET scope_area='AREA-A',iam_zone='zone-a'");assertion.put("zone","zone-a");
        String t=token("area-a");
        http.perform(get("/overview/summary").param("zone","ALL").header("Authorization","Bearer "+t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.zone").value("A"));
        http.perform(get("/overview/summary").param("zone","B").header("Authorization","Bearer "+t)).andExpect(status().isForbidden());
        http.perform(get("/raw-quality/summary").param("zone","A").header("Authorization","Bearer "+t)).andExpect(status().isForbidden());
        http.perform(get("/equipment-profiles/OUTSIDE").header("Authorization","Bearer "+t)).andExpect(status().isForbidden());
    }
    @Test void localLogoutRevokesOldBearer()throws Exception{
        String t=token("logout");
        http.perform(post("/logout").header("Authorization","Bearer "+t)).andExpect(status().isOk());
        http.perform(get("/getInfo").header("Authorization","Bearer "+t)).andExpect(status().isUnauthorized());
    }
    @Test void iamLogoutIsAuthenticatedIdempotentAndSubjectScoped()throws Exception{
        String t=token("iam-logout");
        String payload=JSON.writeValueAsString(Map.of("subject",SUBJECT,"iamSessionId","iam-session-1","audience","emcs"));
        http.perform(post("/api/auth/sso/logout-notify").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
        assertTrue(sessions.findActive(t).isPresent());
        for(int n=0;n<2;n++)http.perform(post("/api/auth/sso/logout-notify").header("X-SSO-Service-Token",SERVICE).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        http.perform(get("/getInfo").header("Authorization","Bearer "+t)).andExpect(status().isUnauthorized());
        exchange("after-logout",401);
    }
    @Test void logoutBeforeExchangeCreatesTombstone()throws Exception{
        sessions.revokeIamSession(SUBJECT,"iam-session-1");exchange("racing-logout",401);
        assertEquals(0,DB.jdbc.queryForObject("SELECT COUNT(*) FROM emcs_sso_session",Integer.class));
    }
    @Test void bindingChangesExpirationAndRestartRepositoryInvalidateCredentials()throws Exception{
        String t=token("session");
        var fresh=new EmcsSsoSessionRepository(DB.jdbc,new EmcsSsoBindingRepository(DB.jdbc),properties);
        assertTrue(fresh.findActive(t).isPresent());
        DB.jdbc.update("UPDATE emcs_sso_subject_map SET read_only=0");assertTrue(fresh.findActive(t).isEmpty());
        DB.jdbc.update("UPDATE emcs_sso_subject_map SET read_only=1");
        assertTrue(fresh.findActive(t).isEmpty(),"Observed revocation must not be undone by restoring the binding");
        String expiring=token("expires");
        DB.jdbc.update("UPDATE emcs_sso_session SET expires_at_epoch=?",Instant.now().minusSeconds(1).getEpochSecond());
        assertTrue(fresh.findActive(expiring).isEmpty());
    }
    @Test void nativeCaptchaLoginStillWorksAndInvalidCaptchaIsRejected()throws Exception{
        when(captcha.consume("u","good")).thenReturn(true);
        http.perform(post("/login").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("username","operator").param("password","local-test-password").param("uuid","u").param("code","wrong"))
                .andExpect(jsonPath("$.code").value(500));
        var result=http.perform(post("/login").contentType(MediaType.APPLICATION_FORM_URLENCODED).param("username","operator").param("password","local-test-password").param("uuid","u").param("code","good"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andReturn();
        String nativeToken=JSON.readTree(result.getResponse().getContentAsString()).path("token").asText();
        http.perform(get("/getInfo").header("Authorization","Bearer "+nativeToken)).andExpect(status().isOk()).andExpect(jsonPath("$.sso.enabled").value(false));
        verify(captcha).consume("u","good");verify(captcha).consume("u","wrong");assertEquals(0,CALLS.get());
    }
    @Test void disabledSsoAndQueryTicketsCannotBypassConfiguration()throws Exception{
        properties.setEnabled(false);exchange("disabled",503);assertEquals(0,CALLS.get());
        properties.setEnabled(true);
        http.perform(post("/api/auth/sso/exchange").queryParam("ticket","do-not-log")).andExpect(status().isBadRequest());
        http.perform(get("/getInfo")).andExpect(status().isUnauthorized());
        http.perform(get("/getInfo").header("X-User-Id","42").header("superAdmin","true")).andExpect(status().isUnauthorized());
    }
    @Test void removedLocalPermissionsAreEffectiveOnNextRequest()throws Exception{
        String t=token("permissions");
        http.perform(get("/overview/summary").header("Authorization","Bearer "+t)).andExpect(status().isOk());
        DB.jdbc.update("UPDATE sys_role SET status='1' WHERE role_id=10");
        http.perform(get("/overview/summary").header("Authorization","Bearer "+t)).andExpect(status().isForbidden());
    }
    @Test void concurrentReplayHasOneWinnerAndEmptyIamScopeIsRejected()throws Exception{
        var workers=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var calls=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for(int i=0;i<4;i++)calls.add(workers.submit(()->http.perform(post("/api/auth/sso/exchange").contentType(MediaType.APPLICATION_JSON).content("{\"ticket\":\"concurrent\"}")).andReturn().getResponse().getStatus()));
            var codes=new java.util.ArrayList<Integer>();for(var result:calls)codes.add(result.get());
            assertEquals(1,codes.stream().filter(code->code==200).count());
            assertEquals(3,codes.stream().filter(code->code==401).count());
        } finally { workers.shutdownNow(); }
        assertion.put("zone","");exchange("empty-zone",401);
    }
    @Test void logoutForOneSubjectCannotRevokeAnotherWithSameSessionLabel()throws Exception{
        String original=token("first-subject");
        DB.jdbc.update("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES(43,'other','Other','0','0')");
        DB.jdbc.update("INSERT INTO emcs_sso_subject_map(iam_subject,local_user_id,scope_area,iam_domain,iam_zone) VALUES(?,43,'*','test-domain','ALL')",OTHER);
        assertion.put("subject",OTHER);assertion.put("localSubject","other");String other=token("second-subject");
        sessions.revokeIamSession(SUBJECT,"iam-session-1");
        assertTrue(sessions.findActive(original).isEmpty());assertTrue(sessions.findActive(other).isPresent());
    }
}
