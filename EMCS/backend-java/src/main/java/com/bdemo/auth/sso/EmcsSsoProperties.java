package com.bdemo.auth.sso;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix="emcs.sso")
public class EmcsSsoProperties {
    private boolean enabled=false, allowInsecureHttp=false;
    private String iamBaseUrl="", serviceToken="";
    private long timeoutMs=3000, sessionMinutes=15;
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public boolean isAllowInsecureHttp(){return allowInsecureHttp;} public void setAllowInsecureHttp(boolean v){allowInsecureHttp=v;}
    public String getIamBaseUrl(){return iamBaseUrl;} public void setIamBaseUrl(String v){iamBaseUrl=v==null?"":v.trim().replaceAll("/+$","");}
    public String getServiceToken(){return serviceToken;} public void setServiceToken(String v){serviceToken=v==null?"":v.trim();}
    public long getTimeoutMs(){return Math.max(500,Math.min(timeoutMs,10000));} public void setTimeoutMs(long v){timeoutMs=v;}
    public long getSessionMinutes(){return Math.max(1,Math.min(sessionMinutes,60));} public void setSessionMinutes(long v){sessionMinutes=v;}
    public boolean usable(){
        if(!enabled || serviceToken.length()<32) return false;
        try {
            URI uri=URI.create(iamBaseUrl);
            return uri.getHost()!=null && uri.getUserInfo()==null && uri.getQuery()==null && uri.getFragment()==null
                    && ("https".equals(uri.getScheme()) || allowInsecureHttp && "http".equals(uri.getScheme()));
        } catch(IllegalArgumentException e){return false;}
    }
    public boolean serviceAuthorized(String provided){
        return usable() && provided!=null && MessageDigest.isEqual(serviceToken.getBytes(StandardCharsets.UTF_8),provided.getBytes(StandardCharsets.UTF_8));
    }
}
