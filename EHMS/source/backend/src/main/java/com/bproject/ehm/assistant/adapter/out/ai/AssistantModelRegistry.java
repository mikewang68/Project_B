package com.bproject.ehm.assistant.adapter.out.ai;

import com.bproject.ehm.assistant.ports.AssistantModelPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.*;

/** Immutable allowlist. Selection is per request/session, never a mutable global provider. */
@Component
@Primary
public class AssistantModelRegistry implements AssistantModelPort {
    private final Map<String, AssistantModelPort> models;
    private final String defaultId;

    public AssistantModelRegistry(ObjectMapper mapper, Environment env, OpenAiCompatibleAssistantAdapter legacy) {
        boolean legacyDeepseek = env.getProperty("ehm.ai.base-url", "https://api.deepseek.com").startsWith("https://api.deepseek.com");
        LinkedHashMap<String, AssistantModelPort> entries = new LinkedHashMap<>();
        entries.put("lab-qwen", configured(env,"lab") ? adapter(mapper,env,"lab","实验室千问",
                "https://lab-model.example/v1","Qwen3.8-27B",false) : !legacyDeepseek ? legacy
                : adapter(mapper,env,"lab","实验室千问","https://lab-model.example/v1","Qwen3.8-27B",false));
        entries.put("deepseek", configured(env,"deepseek") ? adapter(mapper,env,"deepseek","DeepSeek",
                "https://api.deepseek.com","deepseek-chat",true) : legacyDeepseek ? legacy
                : adapter(mapper,env,"deepseek","DeepSeek","https://api.deepseek.com","deepseek-chat",true));
        this.models=Collections.unmodifiableMap(entries);
        this.defaultId=env.getProperty("ehm.ai.default-model",legacyDeepseek?"deepseek":"lab-qwen");
        if(!models.containsKey(defaultId)) throw new IllegalArgumentException("ehm.ai.default-model must be a registered model identifier");
    }
    private static boolean configured(Environment env,String name) {
        return List.of("enabled","api-key-file","api-key","model","base-url").stream()
                .anyMatch(k->env.containsProperty("ehm.ai."+name+"."+k));
    }
    private static AssistantModelPort adapter(ObjectMapper mapper,Environment env,String name,String label,String base,String model,boolean tools) {
        String p="ehm.ai."+name+".";
        return new OpenAiCompatibleAssistantAdapter(mapper,env.getProperty(p+"enabled",Boolean.class,false),
            env.getProperty(p+"base-url",base),env.getProperty(p+"api-key",""),env.getProperty(p+"api-key-file",""),
            env.getProperty(p+"model",model),env.getProperty(p+"data-policy",env.getProperty("ehm.ai.data-policy","disabled")),
            env.getProperty(p+"timeout-seconds",Long.class,45L),label,env.getProperty(p+"trusted-http-origin",""),
            env.getProperty(p+"tools-enabled",Boolean.class,tools));
    }
    @Override public AssistantModelPort select(String id) {
        AssistantModelPort selected=models.get(id==null||id.isBlank()||id.equals("default")?defaultId:id);
        if(selected==null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"未知模型，请从模型列表选择");
        return selected;
    }
    @Override public List<ModelOption> catalog() {
        return models.entrySet().stream().map(e->new ModelOption(e.getKey(),e.getKey().equals("lab-qwen")?"实验室千问":"DeepSeek",e.getValue().status())).toList();
    }
    @Override public String defaultModelId(){return defaultId;}
    @Override public ModelStatus status(){return select(null).status();}
    @Override public boolean supportsToolCalls(){return select(null).supportsToolCalls();}
    @Override public Completion complete(List<Map<String,Object>> messages,List<Map<String,Object>> tools){return select(null).complete(messages,tools);}
}
