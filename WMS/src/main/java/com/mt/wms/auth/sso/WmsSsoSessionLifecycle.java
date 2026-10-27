package com.mt.wms.auth.sso;

import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;

/** Keep the single-instance revocation index aligned with servlet session rotation/destruction. */
@Component
public final class WmsSsoSessionLifecycle implements HttpSessionListener,HttpSessionIdListener {
    @Override public void sessionDestroyed(HttpSessionEvent event){WmsSsoLogoutRegistry.unbind(event.getSession().getId());}
    @Override public void sessionIdChanged(HttpSessionEvent event,String oldId){
        var binding=WmsSsoLogoutRegistry.get(oldId);
        if(binding!=null){
            WmsSsoLogoutRegistry.bind(event.getSession().getId(),binding.iamSessionId(),binding.iamSubject());
            WmsSsoLogoutRegistry.unbind(oldId);
        }
    }
}
