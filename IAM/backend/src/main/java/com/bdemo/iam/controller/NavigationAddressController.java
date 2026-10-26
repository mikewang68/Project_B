package com.bdemo.iam.controller;

import com.bdemo.iam.common.R;
import com.bdemo.iam.navigation.service.NavigationAddressPublicationService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/navigation/addresses")
public class NavigationAddressController {
  private final NavigationAddressPublicationService addresses;
  public NavigationAddressController(NavigationAddressPublicationService addresses){this.addresses=addresses;}
  public record Draft(long expectedVersion,NavigationAddressPublicationService.Address address){}
  @GetMapping public R<List<Map<String,Object>>> list(){return R.ok(addresses.list());}
  @GetMapping("/{app}/history")public R<List<Map<String,Object>>> history(@PathVariable String app){return R.ok(addresses.history(app));}
  @PostMapping("/{app}/draft")public R<Map<String,Object>> draft(@PathVariable String app,@RequestBody Draft request){return R.ok(addresses.draft(app,request.expectedVersion(),request.address()));}
  @PostMapping("/revisions/{id}/validate")public R<Map<String,Object>> validate(@PathVariable String id){return R.ok(addresses.validate(id));}
  @PostMapping("/revisions/{id}/publish")public R<Map<String,Object>> publish(@PathVariable String id){return R.ok(addresses.publish(id));}
  @PostMapping("/revisions/{id}/rollback")public R<Map<String,Object>> rollback(@PathVariable String id){return R.ok(addresses.rollback(id));}
}
