package com.bproject.ehm.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.MediaType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Local server-profile contract probes, not a laboratory/openGauss/SSO acceptance test. */
@SpringBootTest(properties={
    "ehm.auth.mode=disabled",
    "spring.datasource.url=jdbc:h2:mem:ehm-e21-handoff;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "ehm.persistence.auto-ddl=true", "ehm.audit.endpoint=", "ehm.audit.domain=e21-test", "ehm.audit.zone=zone-a",
    "ehm.audit.iam-me-url=", "ehm.audit.iam-required=false", "ehm.ai.enabled=false",
    "ehm.ai.api-key=", "ehm.ai.api-key-file=", "ehm.ai.data-policy=disabled",
    "ehm.config.path=target/nonexistent-e21-test-config.json", "ehm.cors.allowed-origins=http://localhost:8088"
})
@ActiveProfiles("server") @AutoConfigureMockMvc @TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E21HandoffReadOnlyContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    private final List<Map<String,Object>> checks=new ArrayList<>();
    @BeforeAll void syntheticFixtureInPrivateTestDatabase() throws Exception {
        mvc.perform(post("/api/ehm/v1/devices").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"E21-SYNTHETIC-01\",\"name\":\"E21 synthetic fixture\",\"area\":\"test-area\",\"health\":80}"))
                .andExpect(status().isCreated());
    }
    private void capture(String path,MvcResult result,String shape,String explanation) {
        checks.add(Map.of("method","GET","path",path,"httpStatus",result.getResponse().getStatus(),
                "shape",shape,"result","PASS","observedAt",OffsetDateTime.now().toString(),"scope",explanation));
    }
    @Test void devicesUsesPagedDatabaseResponse() throws Exception {
        String path="/api/ehm/v1/devices?page=0&size=20";
        var result=mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].code").value("E21-SYNTHETIC-01"))
                .andExpect(jsonPath("$.totalElements").value(1)).andReturn();
        capture(path,result,"PageResult<DeviceView>","Synthetic test database fixture; pagination response verified, not live equipment.");
    }
    @Test void analyticsReadsSyntheticBusinessRecords() throws Exception {
        String path="/api/ehm/v1/analytics/overview?days=30";
        var result=mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.devices").value(1))
                .andExpect(jsonPath("$.dailyEvents.length()").value(30)).andReturn();
        var body=mapper.readTree(result.getResponse().getContentAsByteArray());
        assertEquals(1,body.path("healthDistribution").path("健康 80–100").asInt());
        capture(path,result,"AnalyticsView","Computed from isolated H2 fixture; source label openGauss is not actual database evidence.");
    }
    @Test void assistantStatusIsHonestWhenDisabled() throws Exception {
        String path="/api/ehm/v1/assistant/status";
        var result=mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.keyConfigured").value(false)).andReturn();
        capture(path,result,"ModelStatus","AI disabled, no keys, no model HTTP call; configuration status only.");
    }
    @Test void analyticsRejectsInvalidRange() throws Exception {
        String path="/api/ehm/v1/analytics/overview?days=0";
        var result=mvc.perform(get(path)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andReturn();
        capture(path,result,"ApiError","Invalid days returns 400; not a server-side 500.");
    }
    @ParameterizedTest @ValueSource(strings={"assets","sites","zones"})
    void unimplementedCollectionRoutesAre404NotFakeSuccess(String collection) throws Exception {
        String path="/api/ehm/v1/"+collection;
        var result=mvc.perform(get(path)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND")).andReturn();
        capture(path,result,"ApiError","Route absent; correct 404 is a negative contract test, not an implemented feature.");
    }
    @AfterAll void exportSanitizedProbeResults() throws Exception {
        checks.sort(Comparator.comparing(c->String.valueOf(c.get("path"))));
        var report=new LinkedHashMap<String,Object>();
        report.put("verifiedAt",OffsetDateTime.now().toString());
        report.put("environment","LOCAL_SPRING_MOCKMVC_SERVER_PROFILE_H2_NOT_LABORATORY");
        report.put("transport","MockMvc in-process dispatch, NOT a real remote HTTP server");
        report.put("fixture","One synthetic device created only in an isolated in-memory test database; GET probes also create audit records");
        report.put("authentication","IAM verification optional/disabled in this test; no SSO or business RBAC acceptance");
        report.put("checks",checks);
        report.put("notVerified",List.of("laboratory deployment","real openGauss/openGemini","Portal/BFF","browser deep links","IAM SSO","business write-denial/data-scope isolation","model connectivity","blockchain"));
        Files.createDirectories(Path.of("target"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/e21-readonly-probes.json").toFile(),report);
    }
}
