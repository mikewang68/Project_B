package com.mt.wms.integration;

import com.bproject.iam.context.SharedDispatchClient;
import com.fasterxml.jackson.databind.*;
import com.mt.wms.auth.sso.*;
import com.mt.wms.common.api.ApiResponse;
import com.mt.wms.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/shared-dispatch")
@ConditionalOnProperty(name="wms.shared-dispatch.enabled",havingValue="true")
public class SharedDispatchController {
  private final SharedDispatchClient client;
  private final ObjectMapper mapper;
  public SharedDispatchController(WmsSsoProperties sso,ObjectMapper mapper,
      @Value("${wms.shared-dispatch.base-url:}") String base,
      @Value("${wms.shared-dispatch.allow-loopback-http:false}") boolean loopback) {
    this.mapper=mapper;
    this.client=new SharedDispatchClient(base,"wms",sso.getServiceToken(),loopback);
  }
  @GetMapping({"/{resource:plans|work-orders}","/{resource:plans|work-orders}/{id}"})
  @PreAuthorize("hasAuthority('stockin:read')")
  public ApiResponse<JsonNode> read(@PathVariable String resource,@PathVariable(required=false) String id,
      @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize,
      HttpServletRequest request) {
    var local=request.getSession(false);
    var binding=local==null?null:WmsSsoLogoutRegistry.get(local.getId());
    var context=WmsCentralScope.current(request);
    if(binding==null||context==null||!context.hasAction("WMS:stockin","VIEW"))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"共享读取要求当前中央业务授权");
    try {
      var body=mapper.readTree(client.read(binding.iamSubject(),binding.iamSessionId(),resource,id,page,pageSize));
      if(!body.path("ok").asBoolean()||!body.path("data").path("sourceApp").asText().equals("PSMS"))
        throw new SharedDispatchClient.Rejected(503);
      return ApiResponse.ok(body.path("data"),String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }catch(SharedDispatchClient.Rejected rejected) {
      throw new ResponseStatusException(HttpStatus.valueOf(rejected.status()),rejected.getMessage());
    }catch(java.io.IOException invalid) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"共享读取响应无效");
    }
  }
  @ExceptionHandler(ResponseStatusException.class)
  public org.springframework.http.ResponseEntity<ApiResponse<Void>> rejected(ResponseStatusException failure,
      HttpServletRequest request) {
    return org.springframework.http.ResponseEntity.status(failure.getStatusCode())
        .body(ApiResponse.error("SHARED_READ_REJECTED","共享读取不可用或未获授权",
            String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE))));
  }
}
