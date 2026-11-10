package com.bproject.safety.config;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import org.springframework.web.context.request.*;

/** Validate the entire machine batch before any controller/cache/database side effect. */
@ControllerAdvice
public class ScsMachineBodyAdvice extends RequestBodyAdviceAdapter {
  private final ObjectMapper mapper;private final NamedParameterJdbcTemplate jdbc;
  public ScsMachineBodyAdvice(ObjectMapper mapper,@org.springframework.beans.factory.annotation.Autowired(required=false) NamedParameterJdbcTemplate jdbc){this.mapper=mapper;this.jdbc=jdbc;}
  public boolean supports(MethodParameter p,Type type,Class<? extends HttpMessageConverter<?>> converter){return ScsMachineRegistry.current()!=null;}
  public HttpInputMessage beforeBodyRead(HttpInputMessage input,MethodParameter parameter,Type type,Class<? extends HttpMessageConverter<?>> converter)throws IOException{
    byte[] bytes=input.getBody().readNBytes(262145);if(bytes.length>262144)throw ScsMachineRegistry.denied();
    JsonNode body;try{body=mapper.readTree(bytes);}catch(Exception e){throw ScsMachineRegistry.denied();}
    var binding=ScsMachineRegistry.current();if(binding==null)throw ScsMachineRegistry.denied();binding.revalidate();
    String path=((ServletRequestAttributes)RequestContextHolder.currentRequestAttributes()).getRequest().getRequestURI();
    validate(binding,path,body);
    return new HttpInputMessage(){public InputStream getBody(){return new ByteArrayInputStream(bytes);}public HttpHeaders getHeaders(){return input.getHeaders();}};
  }
  void validate(ScsMachineRegistry.Binding binding,String path,JsonNode body){
    if(jdbc==null||body==null||!body.isObject()||forgedTuple(body))throw ScsMachineRegistry.denied();
    if(body.has("source")&&!binding.id().equals(body.path("source").asText()))throw ScsMachineRegistry.denied();
    // AI 视觉事件为扁平单对象（无批数组）：按绑定持有的 CAMERA 对象校验摄像头归属与存在性。
    if("/api/v1/ai-events/ingest".equals(path)){
      JsonNode camera=body.path("cameraCode");
      if(!camera.isTextual()||camera.asText().isBlank())throw ScsMachineRegistry.denied();
      String code=camera.asText().trim();
      if(!binding.owns(ScsCentralScope.Root.CAMERA,code))throw ScsMachineRegistry.denied();
      var scope=binding.scope();
      Long count=jdbc.queryForObject("SELECT count(*) FROM safety."+ScsCentralScope.Root.CAMERA.table+" WHERE "+ScsCentralScope.Root.CAMERA.key+"=:c AND project_id=:p AND region_id=:r AND subsystem_code=:s",Map.of("c",code,"p",scope.projectId(),"r",scope.regionId(),"s",scope.subsystemCode()),Long.class);
      if(count==null||count!=1)throw ScsMachineRegistry.denied();
      return;
    }
    String array,key,prefix="";ScsCentralScope.Root root;
    switch(path){
      case "/api/v1/ingest/person-position"->{array="positions";key="id";root=ScsCentralScope.Root.PERSONNEL;}
      case "/api/v1/ingest/device-state"->{array="devices";key="code";root=ScsCentralScope.Root.COLLISION;}
      case "/api/v1/ingest/zone-state"->{array="zones";key="code";root=ScsCentralScope.Root.AREA;}
      case "/api/v1/ingest/uwb-station"->{array="stations";key="stationId";prefix="UWB:";root=ScsCentralScope.Root.RUNTIME;}
      default->throw ScsMachineRegistry.denied();
    }
    var entries=body.path(array);if(!entries.isArray()||entries.isEmpty()||entries.size()>1000)throw ScsMachineRegistry.denied();
    Set<String> ids=new HashSet<>();for(var item:entries){String id=prefix+item.path(key).asText();if(!binding.owns(root,id)||!ids.add(id))throw ScsMachineRegistry.denied();}
    var scope=binding.scope();
    Long count=jdbc.queryForObject("SELECT count(*) FROM safety."+root.table+" WHERE "+root.key+" IN (:ids) AND project_id=:p AND region_id=:r AND subsystem_code=:s",Map.of("ids",ids,"p",scope.projectId(),"r",scope.regionId(),"s",scope.subsystemCode()),Long.class);
    if(count==null||count!=ids.size())throw ScsMachineRegistry.denied();
  }
  private boolean forgedTuple(JsonNode node){
    if(node.isObject()){var fields=node.fields();while(fields.hasNext()){var f=fields.next();if(Set.of("project_id","region_id","subsystem_code","projectId","regionId","subsystemCode","userContext","my_info").contains(f.getKey())||forgedTuple(f.getValue()))return true;}}
    else if(node.isArray())for(var value:node)if(forgedTuple(value))return true;
    return false;
  }
}
