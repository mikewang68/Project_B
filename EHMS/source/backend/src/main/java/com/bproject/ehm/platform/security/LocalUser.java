package com.bproject.ehm.platform.security;
import java.security.Principal;

public record LocalUser(String userId,String username,String displayName,String role,String csrfToken) implements Principal {
    @Override public String getName(){return userId;}
    public boolean admin(){return role.equals("SUPER_ADMIN");}
    public java.util.Map<String,Object> view(){return java.util.Map.of("id",userId,"username",username,"displayName",displayName,"role",role,"roleName",admin()?"超级管理员":"一般用户");}
}
