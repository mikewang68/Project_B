package com.bproject.ehm.platform.security;

import com.bproject.iam.context.SharedDispatchClient;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @Profile("server")
@ConditionalOnProperty(name="ehm.shared-dispatch.enabled",havingValue="true")
@RequestMapping("/api/ehm/v1/shared-dispatch")
public class SharedDispatchController {
  private final SharedDispatchClient client;private final ObjectMapper mapper;
  public SharedDispatchController(ObjectMapper mapper,
      @Value("${ehm.shared-dispatch.base-url:}") String base,
      @Value("${ehm.sso.service-token:}") String token,
      @Value("${ehm.shared-dispatch.allow-loopback-http:false}") boolean loopback) {
    this.mapper=mapper;client=new SharedDispatchClient(base,"ehms",token,loopback);
  }
  @GetMapping({"/{resource:plans|work-orders}","/{resource:plans|work-orders}/{id}"})
  public Map<String,Object> read(@PathVariable String resource,@PathVariable(required=false) String id,
      @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize,HttpServletRequest request) {
    if(!(request.getUserPrincipal() instanceof EhmSsoSessions.Actor actor)||actor.context()==null
        ||!EhmBusinessPermissions.permits(actor,"GET",request.getRequestURI()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"共享读取要求当前中央及原生业务授权");
    try {
      var body=mapper.readTree(client.read(actor.subject(),actor.iamSid(),resource,id,page,pageSize));
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
