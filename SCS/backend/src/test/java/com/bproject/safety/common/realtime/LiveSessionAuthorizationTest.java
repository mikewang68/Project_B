package com.bproject.safety.common.realtime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bproject.safety.config.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class LiveSessionAuthorizationTest {
  ScsIamTicketValidator iam=mock(ScsIamTicketValidator.class);ScsLocalPolicy policy=mock(ScsLocalPolicy.class);
  LiveSessionAuthorization service=new LiveSessionAuthorization(iam,policy,true);
  @Test void anonymousSocketCannotConsumeEvents(){var socket=mock(WebSocketSession.class);when(socket.getAttributes()).thenReturn(Map.of());assertThrows(IllegalStateException.class,()->service.current(socket));}
  @Test void eventPermissionsAreIndependent(){var actor=new ScsLocalPolicy.Actor("subject","safety",Set.of("OPERATOR"),Set.of("safety:module:alert:view"),false,false);assertTrue(service.permits(actor,"alert.changed"));assertFalse(service.permits(actor,"rule.published"));assertFalse(service.permits(actor,"ai.reviewed"));assertFalse(service.permits(actor,"unknown.event"));}
  @Test void revocationCannotBeBypassedWithAnOldBinding(){String local=UUID.randomUUID().toString();ScsSessionRegistry.bind(local,"subject","central",Set.of(),Set.of(),"scs");var binding=ScsSessionRegistry.get(local);ScsSessionRegistry.revokeByIamSid("central");var socket=mock(WebSocketSession.class);when(socket.getAttributes()).thenReturn(Map.of("SCS_WS_BINDING",binding));assertThrows(IllegalStateException.class,()->service.current(socket));verifyNoInteractions(iam);}
}
