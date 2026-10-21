package com.bproject.ehm.platform.audit.application;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AuditRequestMetadataTest {
    @Test void lifecycleCommandsAreNotCreates() {
        for (String path : new String[]{"shift-handovers/H1/submit", "shift-handovers/H1/receive",
                "spare-parts/P1/receipt", "spare-parts/P1/return", "inspection-tasks/T1/submit",
                "alarms/A1/case/diagnoses", "work-orders/W1/case/retests", "assistant/chat"})
            assertEquals("EXECUTE", AuditRequestMetadata.action("POST", "/api/ehm/v1/" + path), path);
        assertEquals("CREATE", AuditRequestMetadata.action("POST","/api/ehm/v1/inspection-tasks"));
        assertEquals("CREATE", AuditRequestMetadata.action("POST","/api/ehm/v1/devices"));
        assertEquals("READ", AuditRequestMetadata.action("GET","/api/ehm/v1/shift-handovers/H1/receive"));
    }
    @Test void distinguishesCrudExportAndBackup() {
        assertEquals("UPDATE",AuditRequestMetadata.action("PATCH","/api/ehm/v1/work-orders/W1/status"));
        assertEquals("DELETE",AuditRequestMetadata.action("DELETE","/api/ehm/v1/devices/D1"));
        assertEquals("EXPORT",AuditRequestMetadata.action("GET","/api/ehm/v1/devices/export"));
        assertEquals("BACKUP",AuditRequestMetadata.action("POST","/api/ehm/v1/system/backup"));
        assertEquals("RESTORE",AuditRequestMetadata.action("POST","/api/ehm/v1/system/restore"));
    }
    @Test void usesMatchedTargetRatherThanCategoryOrParent() {
        var request=new MockHttpServletRequest("PUT","/api/ehm/v1/assets/A1/components/C1");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,Map.of("assetCode","A1","componentCode","C1"));
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/api/ehm/v1/assets/{assetCode}/components/{componentCode}");
        assertEquals("C1",AuditRequestMetadata.objectId(request));
        assertEquals("/api/ehm/v1/assets/{assetCode}/components/{componentCode}",AuditRequestMetadata.route(request));
    }
    @Test void unknownRouteDoesNotCaptureArbitrarySensitivePathOrQuery() {
        var request=new MockHttpServletRequest("GET","/api/ehm/v1/devices/sk-supersecret123456789");
        request.setQueryString("token=private");
        assertEquals("",AuditRequestMetadata.objectId(request));
        assertEquals("/api/ehm/v1/devices/{unresolved}",AuditRequestMetadata.route(request));
    }
}
