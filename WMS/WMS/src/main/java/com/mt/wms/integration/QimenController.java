package com.mt.wms.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.common.api.ApiResponse;
import com.mt.wms.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
class QimenController {
    private final QimenService service;private final ObjectMapper mapper;
    QimenController(QimenService service,ObjectMapper mapper){this.service=service;this.mapper=mapper;}

    @GetMapping("/api/v1/integration/qimen/config") @PreAuthorize("hasAuthority('integration:manage')") ApiResponse<IntegrationModels.QimenConfigView> config(Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.config(p(a),s),id(r));}
    @PutMapping("/api/v1/integration/qimen/config") @PreAuthorize("hasAuthority('integration:manage')") ApiResponse<IntegrationModels.QimenConfigView> save(@RequestBody IntegrationModels.QimenConfigRequest b,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.save(b,p(a),s),id(r));}
    @GetMapping("/api/v1/integration/qimen/logs") @PreAuthorize("hasAuthority('integration:manage')") ApiResponse<List<IntegrationModels.QimenLogView>> logs(Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.logs(p(a),s),id(r));}
    @PostMapping("/api/v1/integration/qimen/confirm/{inout}/{orderId}") @PreAuthorize("hasAuthority('integration:manage')") ApiResponse<Map<String,Object>> confirm(@PathVariable String inout,@PathVariable long orderId,Authentication a,HttpSession s,HttpServletRequest r){return ApiResponse.ok(service.confirm(inout,orderId,p(a),s),id(r));}

    @RequestMapping(value="/open/qimen",method={RequestMethod.GET,RequestMethod.POST,RequestMethod.PUT,RequestMethod.PATCH,RequestMethod.DELETE},produces=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> inbound(@RequestParam Map<String,String> query,@RequestBody(required=false)String body){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(service.inbound(query,body==null?"{}":body,false));}
    @RequestMapping(value="/open/qimen/xml",method={RequestMethod.GET,RequestMethod.POST,RequestMethod.PUT,RequestMethod.PATCH,RequestMethod.DELETE},produces=MediaType.APPLICATION_XML_VALUE)
    ResponseEntity<String> inboundXml(@RequestParam Map<String,String> query,@RequestBody(required=false)String body)throws Exception{String json=service.inbound(query,body==null?"<request/>":body,true);Object value=mapper.readValue(json,LinkedHashMap.class);return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(new XmlMapper().writer().withRootName("qimen").writeValueAsString(value));}
    @RequestMapping(value={"/open/qimen/backtest","/open/qimen/backtest/xml"},method={RequestMethod.GET,RequestMethod.POST}) ResponseEntity<String> backtest(HttpServletRequest request){boolean xml=request.getRequestURI().endsWith("/xml");String json="{\"response\":{\"flag\":\"success\",\"code\":\"0\",\"message\":\"ok\"}}";return ResponseEntity.ok().contentType(xml?MediaType.APPLICATION_XML:MediaType.APPLICATION_JSON).body(xml?"<response><flag>success</flag><code>0</code><message>ok</message></response>":json);}
    private static WmsPrincipal p(Authentication a){return (WmsPrincipal)a.getPrincipal();}
    private static String id(HttpServletRequest r){return (String)r.getAttribute(RequestIdFilter.ATTRIBUTE);}
}
