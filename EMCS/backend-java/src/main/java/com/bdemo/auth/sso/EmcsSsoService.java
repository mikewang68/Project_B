package com.bdemo.auth.sso;

import com.bdemo.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** IAM V1.1 is the only ticket verifier/consumer. Never decode browser claims for authorization. */
@Service
public class EmcsSsoService {
    private final EmcsSsoProperties properties;
    private final ObjectMapper json;
    private final EmcsSsoSessionRepository sessions;
    private final HttpClient http;
    public EmcsSsoService(EmcsSsoProperties properties,ObjectMapper json,EmcsSsoSessionRepository sessions){
        this.properties=properties;this.json=json;this.sessions=sessions;
        this.http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.getTimeoutMs())).build();
    }
    public record Assertion(String subject,String sessionId,String localSubject,String company,String org,String domain,String zone){}
    public EmcsSsoSessionRepository.Issued exchange(String ticket){
        if(!properties.usable())throw new BusinessException(503,"EMCS可信SSO未配置");
        if(ticket==null || ticket.isBlank() || ticket.length()>8192)throw new BusinessException(400,"SSO票据缺失或长度无效");
        JsonNode root;
        try {
            var request=HttpRequest.newBuilder(URI.create(properties.getIamBaseUrl()+"/auth/sso/verify-and-consume"))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type","application/json").header("Accept","application/json")
                    .header("X-SSO-Service-Token",properties.getServiceToken())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("ticket",ticket,"audience","emcs","consumer","emcs")))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==401 || response.statusCode()==403)throw new BusinessException(401,"IAM拒绝SSO票据");
            if(response.statusCode()!=200 || response.body().length()>65536)throw new BusinessException(503,"IAM验票服务不可用");
            root=json.readTree(response.body());
        } catch(BusinessException e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new BusinessException(503,"IAM验票被中断");}
        catch(Exception e){throw new BusinessException(503,"IAM验票服务不可用");}
        if(root==null || !root.path("code").isIntegralNumber() || !root.path("code").canConvertToInt() || root.path("code").asInt(-1)!=0)
            throw new BusinessException(401,"IAM未确认票据有效");
        JsonNode a=root.path("data");
        // Current IAM serializes Claims.getAudience() using Set.toString(), yielding [emcs].
        String audience=text(a,"audience",32,true);
        if(!"emcs".equals(audience) && !"[emcs]".equals(audience))throw new BusinessException(401,"SSO票据受众不符");
        if(!"1.1".equals(text(a,"contractVersion",16,true)))throw new BusinessException(401,"SSO契约版本不符");
        String subject=text(a,"subject",64,true),sid=text(a,"sessionId",128,true);
        try{if(!UUID.fromString(subject).toString().equalsIgnoreCase(subject))throw new IllegalArgumentException();}
        catch(IllegalArgumentException e){throw new BusinessException(401,"SSO主体格式无效");}
        return sessions.issue(new Assertion(subject,sid,text(a,"localSubject",64,true),text(a,"company",128,false),
                text(a,"org",128,false),text(a,"domain",128,true),text(a,"zone",128,true)));
    }
    private String text(JsonNode object,String key,int maximum,boolean required){
        JsonNode value=object.path(key);
        if(value.isNull() || value.isMissingNode()) {if(!required)return "";throw new BusinessException(401,"SSO断言字段缺失");}
        if(!value.isTextual())throw new BusinessException(401,"SSO断言字段类型无效");
        String result=value.asText();
        if(result.length()>maximum || result.indexOf('\n')>=0 || result.indexOf('\r')>=0 || required && result.isBlank())
            throw new BusinessException(401,"SSO断言字段无效");
        return result;
    }
}
