package com.mt.wms.stockin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Credentials are supplied only by private server configuration, with no usable defaults. */
@Component
public final class PsmsInboundSettings {
    public static final String CALLBACK_URL="http://127.0.0.1:20910/api/integrations/wms/inbound-events";
    private final boolean enabled;
    private final String inboundToken,outboundToken,companyCode,ownerCode,projectId,regionId;
    public PsmsInboundSettings(@Value("${WMS_PSMS_ENABLED:false}") boolean enabled,
            @Value("${WMS_PSMS_INBOUND_TOKEN:}") String inboundToken,
            @Value("${WMS_PSMS_OUTBOUND_TOKEN:}") String outboundToken,
            @Value("${WMS_PSMS_COMPANY_CODE:}") String companyCode,
            @Value("${WMS_PSMS_OWNER_CODE:}") String ownerCode,
            @Value("${WMS_PSMS_PROJECT_ID:}") String projectId,
            @Value("${WMS_PSMS_REGION_ID:}") String regionId){
        this.enabled=enabled;this.inboundToken=inboundToken;this.outboundToken=outboundToken;
        this.companyCode=companyCode;this.ownerCode=ownerCode;
        this.projectId=projectId;this.regionId=regionId;
        if(enabled && (!validToken(inboundToken)||!validToken(outboundToken)||inboundToken.equals(outboundToken)
                ||companyCode.isBlank()||ownerCode.isBlank()||projectId.isBlank()||regionId.isBlank()))
            throw new IllegalStateException("PSMS联动需配置两份不同的服务凭据及明确公司、货主、项目、区域范围");
        if(enabled){com.bproject.iam.context.ScopeTriple.identifier(projectId,64);com.bproject.iam.context.ScopeTriple.identifier(regionId,64);}
    }
    private static boolean validToken(String token){return token.length()>=32&&token.length()<=256&&token.chars().allMatch(c->c>=33&&c<=126);}
    public boolean enabled(){return enabled;}
    public String companyCode(){return companyCode;}
    public String ownerCode(){return ownerCode;}
    public String projectId(){return projectId;}
    public String regionId(){return regionId;}
    String outboundToken(){return outboundToken;}
    public boolean accepts(String authorization){
        if(!enabled||authorization==null||!authorization.startsWith("Bearer "))return false;
        return MessageDigest.isEqual(inboundToken.getBytes(StandardCharsets.UTF_8),authorization.substring(7).getBytes(StandardCharsets.UTF_8));
    }
    void requireEnabled(){if(!enabled)throw new PsmsInboundException(503,"PSMS_DISABLED","调度入库联动尚未启用");}
    void requireScope(String company,String owner){
        if(!companyCode.equals(company)||!ownerCode.equals(owner))
            throw new PsmsInboundException(403,"PSMS_SCOPE_FORBIDDEN","公司或货主不在服务授权范围内");
    }
}
