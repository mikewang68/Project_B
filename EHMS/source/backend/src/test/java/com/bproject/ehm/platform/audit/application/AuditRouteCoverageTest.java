package com.bproject.ehm.platform.audit.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:audit-route-coverage;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","ehm.persistence.auto-ddl=true","ehm.audit.domain=local-test","ehm.audit.zone=zone-a","ehm.audit.endpoint=","ehm.audit.iam-me-url=","ehm.audit.iam-required=false","ehm.ai.enabled=false","ehm.ai.api-key=","ehm.ai.api-key-file=","ehm.config.path=target/absent-coverage-config.json"})
@ActiveProfiles("server")
@org.springframework.test.context.TestPropertySource(properties="ehm.auth.mode=disabled")
class AuditRouteCoverageTest {
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;@Autowired ObjectMapper mapper;
    @Test void exportActualRegisteredBusinessRoutesWithHonestCaptureBoundaries() throws Exception {
        List<Map<String,Object>> rows=new ArrayList<>();
        mappings.getHandlerMethods().forEach((mapping,handler)->{
            for(String route:mapping.getPatternValues())if(route.startsWith("/api/ehm/v1/"))for(var method:mapping.getMethodsCondition().getMethods()){
                String pkg=handler.getBeanType().getPackageName();String capture=pkg.contains(".platform.")?"HTTP_RESULT_FALLBACK":pkg.contains(".assistant.")?"ASSISTANT_OUTCOME_AND_HTTP_FALLBACK":"BUSINESS_TRANSACTION_AND_HTTP_FALLBACK";
                var row=new LinkedHashMap<String,Object>();row.put("method",method.name());row.put("route",route);row.put("action",AuditRequestMetadata.action(method.name(),route));row.put("capture",capture);
                row.put("taskJournal",handler.hasMethodAnnotation(AuditedTask.class));row.put("verification","REGISTERED_ROUTE_AND_CAPTURE_RULE_ONLY_NOT_EVERY_OPERATION_EXECUTED");rows.add(row);
            }
        });
        rows.sort(Comparator.comparing(r->r.get("route")+" "+r.get("method")));
        assertFalse(rows.isEmpty());assertTrue(rows.stream().anyMatch(r->r.get("route").equals("/api/ehm/v1/devices")&&r.get("method").equals("POST")));
        assertEquals(3,rows.stream().filter(r->Boolean.TRUE.equals(r.get("taskJournal"))).count());
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/audit-route-coverage.json").toFile(),Map.of("scope","LOCAL_REGISTERED_ROUTES_ONLY","fullRbacVerified",false,"routes",rows,"limitations",List.of("Not a per-operation E2E pass list","CLIENT only records intent","HTTP success does not prove async completion","Identity/scope/grants still need IAM verification","OPTIONS and HEAD are intentionally not business audit operations")));
    }
}
