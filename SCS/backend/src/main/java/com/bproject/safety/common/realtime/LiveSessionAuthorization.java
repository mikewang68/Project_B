package com.bproject.safety.common.realtime;

import com.bproject.safety.config.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.socket.WebSocketSession;

/** Re-check central authorization before delivery; no permissions are trusted from the socket client. */
@Service
public class LiveSessionAuthorization {
  private final ScsIamTicketValidator iam;private final ScsLocalPolicy policy;private final boolean required;
  public LiveSessionAuthorization(ScsIamTicketValidator iam,ScsLocalPolicy policy,@Value("${app.security.browser-sso-only:${app.security.enabled:true}}")boolean required){this.iam=iam;this.policy=policy;this.required=required;}
  public ScsLocalPolicy.Actor current(WebSocketSession session){
    var value=session.getAttributes().get("SCS_WS_BINDING");
    if(!(value instanceof ScsSessionRegistry.Session binding)){
      if(!required)return null;
      throw new IllegalStateException("Missing business session");
    }
    if(ScsSessionRegistry.isSessionRevoked(binding.localSessionId()) || ScsSessionRegistry.get(binding.localSessionId())==null)
      throw new IllegalStateException("Revoked business session");
    return policy.resolve(iam.current(binding.iamSubject(),binding.iamSid()));
  }
  public boolean permits(ScsLocalPolicy.Actor actor,String eventType){
    if(actor==null)return !required;
    if(actor.admin())return true;
    String permission;
    if(eventType.startsWith("alert."))permission="safety:module:alert:view";
    else if(eventType.startsWith("ai."))permission="safety:module:ai:view";
    else if(eventType.startsWith("person."))permission="safety:person:map:view";
    else if(eventType.startsWith("fence."))permission="safety:fence:list:view";
    else if(eventType.startsWith("collision."))permission="safety:module:collision:view";
    else if(eventType.startsWith("rule."))permission="safety:module:rule:view";
    else if(eventType.startsWith("ops."))permission="safety:module:ops:view";
    else return false;
    return actor.permissions().contains(permission);
  }
}
