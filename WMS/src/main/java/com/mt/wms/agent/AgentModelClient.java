package com.mt.wms.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@Component
class AgentModelClient {
    private final ObjectMapper mapper;
    private final String base,key,model;
    private final boolean enabled;
    private final int timeout;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Semaphore slots=new Semaphore(2);
    private final AtomicInteger failures=new AtomicInteger();
    private volatile long retryAfter;
    AgentModelClient(ObjectMapper mapper,@Value("${wms.agent.base-url}")String base,
            @Value("${wms.agent.api-key}")String key,@Value("${wms.agent.model}")String model,
            @Value("${wms.agent.enabled:true}")boolean enabled,@Value("${wms.agent.timeout-seconds:30}")int timeout) {
        this.mapper=mapper;this.base=base.replaceAll("/+$","");this.key=key;this.model=model;this.enabled=enabled;
        this.timeout=Math.min(60,Math.max(5,timeout));
    }
    String model() {return model;}
    boolean configured() {return enabled&&!key.isBlank();}
    JsonNode completion(List<Map<String,Object>> messages,List<Map<String,Object>> tools) {
        if(!configured()) throw new IllegalStateException("智能体模型尚未配置或已禁用");
        if(System.currentTimeMillis()<retryAfter) throw new IllegalStateException("模型暂时不可用，稍后可重试；数据库查询仍可使用");
        if(!slots.tryAcquire()) throw new IllegalStateException("模型正在处理其他任务，请稍后重试");
        try {
            Map<String,Object> body=new LinkedHashMap<>();body.put("model",model);body.put("messages",messages);
            body.put("max_tokens",1400);body.put("temperature",0.15);body.put("stream",false);
            if(!tools.isEmpty()) {body.put("tools",tools);body.put("tool_choice","auto");}
            HttpRequest request=HttpRequest.newBuilder(URI.create(base+"/chat/completions"))
                .timeout(Duration.ofSeconds(timeout)).header("Authorization","Bearer "+key)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) throw new IllegalStateException("模型接口返回 HTTP "+response.statusCode());
            JsonNode msg=mapper.readTree(response.body()).path("choices").path(0).path("message");
            if(msg.isMissingNode()) throw new IllegalStateException("模型返回格式异常");
            failures.set(0);return msg;
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();throw new IllegalStateException("模型请求已中断");
        } catch(Exception e) {
            if(failures.incrementAndGet()>=3) retryAfter=System.currentTimeMillis()+60000;
            if(e instanceof IllegalStateException s) throw s;
            // 不暴露请求头、模型返回正文或密钥。
            throw new IllegalStateException("模型连接失败或请求超时，已保留查询结果，可重试");
        } finally {slots.release();}
    }
}
