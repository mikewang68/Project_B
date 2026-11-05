package com.bdemo.iam.scope;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.sso.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScopeResourceControllerTest {
  final SsoServiceIdentity services=mock(SsoServiceIdentity.class);
  final SsoAuthorizationService authorization=mock(SsoAuthorizationService.class);
  final ScopeResourceGuard guard=mock(ScopeResourceGuard.class);
  final ScopeResourceController controller=new ScopeResourceController(services,authorization,guard);
  final ScopeResourceController.Request request=new ScopeResourceController.Request(
      "subject","session","wms","PSMS:shared:plans","WMS:stockin","VIEW");
  @Test void wrongServiceIdentityNeverReachesSessionOrDataAuthorization() {
    var denied=assertThrows(BizException.class,()->controller.filter(request,"wrong"));
    assertEquals(403,denied.getHttpStatus());verifyNoInteractions(authorization,guard);
  }
  @Test void missingMismatchedOrRevokedSessionIsAnAuthenticationDenial() {
    when(services.authorize("wms","service")).thenReturn(true);
    when(authorization.current("subject","session","wms"))
        .thenThrow(new SsoTicketService.SsoTicketException("expired"));
    var denied=assertThrows(BizException.class,()->controller.filter(request,"service"));
    assertEquals(401,denied.getHttpStatus());verifyNoInteractions(guard);
  }
  @Test void legacySessionCannotRequestAV2ResourceFilter() {
    when(services.authorize("wms","service")).thenReturn(true);
    when(authorization.current("subject","session","wms")).thenReturn(Map.of("active",true));
    var denied=assertThrows(BizException.class,()->controller.filter(request,"service"));
    assertEquals(403,denied.getHttpStatus());verifyNoInteractions(guard);
  }
}
