package com.bdemo.energy.collection;

import com.bdemo.common.AjaxResult;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/collection")
public class CollectionController {
 private final CollectionService service;
 public CollectionController(CollectionService service){this.service=service;}
 @PostMapping(value="/events",consumes="application/json")
 public java.util.Map<String,Object> ingest(@RequestHeader(value="X-EMCS-Ingest-Token",required=false)String token,HttpServletRequest request)throws IOException{
  service.authorize(token);return service.accept(request.getInputStream().readNBytes(262145));
 }
 @GetMapping("/catalog") public AjaxResult catalog(){return AjaxResult.success(service.catalog());}
 @GetMapping("/events") public AjaxResult events(@RequestParam(required=false)String deviceId,@RequestParam(defaultValue="1")int pageNum,
     @RequestParam(defaultValue="20")int pageSize,Authentication auth){return AjaxResult.success(service.list(area(auth),deviceId,pageNum,pageSize));}
 @GetMapping("/event") public AjaxResult event(@RequestParam String eventId,Authentication auth){return AjaxResult.success(service.detail(area(auth),eventId));}
 private String area(Authentication auth){return auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_dispatch"))?"AREA-A":null;}
}
