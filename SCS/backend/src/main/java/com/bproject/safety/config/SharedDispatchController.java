package com.bproject.safety.config;

import com.bproject.iam.context.SharedDispatchClient;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name="app.shared-dispatch.enabled",havingValue="true")
@RequestMapping("/api/v1/shared-dispatch")
public class SharedDispatchController {
  private final SharedDispatchClient client;private final ObjectMapper mapper;
  public SharedDispatchController(ObjectMapper mapper,
      @Value("${app.shared-dispatch.base-url:}") String base,
      @Value("${app.security.iam-service-token:}") String token,
      @Value("${app.shared-dispatch.allow-loopback-http:false}") boolean loopback) {
    this.mapper=mapper;client=new SharedDispatchClient(base,"scs",token,loopback);
  }
  @GetMapping({"/{resource:plans|work-orders}","/{resource:plans|work-orders}/{id}"})
  public Map<String,Object> read(@PathVariable String resource,@PathVariable(required=false) String id,
      @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize,HttpServletRequest request) {
    var session=request.getSession(false);var binding=session==null?null:ScsSessionRegistry.get(session.getId());
    var context=ScsCentralScope.current();
    if(binding==null||context==null||!context.hasAction("SCS:rules","VIEW")
        ||!(request.getAttribute("SCS_ACTOR") instanceof ScsLocalPolicy.Actor actor)
        ||!actor.admin()&&!actor.permissions().contains("safety:module:rule:view"))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"共享读取要求当前中央及原生业务授权");
    try {
      var body=mapper.readTree(client.read(binding.iamSubject(),binding.iamSid(),resource,id,page,pageSize));
      if(!body.path("ok").asBoolean()||!body.path("data").path("sourceApp").asText().equals("PSMS"))
        throw new SharedDispatchClient.Rejected(503);
      return Map.of("code",0,"data",body.path("data"));
    }catch(SharedDispatchClient.Rejected rejected) {
      throw new ResponseStatusException(HttpStatus.valueOf(rejected.status()),rejected.getMessage());
    }catch(java.io.IOException invalid) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"共享读取响应无效");
    }
  }
}
