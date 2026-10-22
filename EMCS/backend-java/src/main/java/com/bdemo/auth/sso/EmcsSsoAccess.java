package com.bdemo.auth.sso;

import com.bdemo.auth.AuthenticatedUser;
public record EmcsSsoAccess(AuthenticatedUser user,String subject,String iamSessionId,boolean readOnly,String scopeArea) {
    public boolean areaRestricted(){return !"*".equals(scopeArea);}
    public boolean effectiveReadOnly(){return readOnly || areaRestricted();}
}
