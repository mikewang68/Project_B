package com.bdemo.energy.suggestion;

import com.bdemo.auth.AuthenticatedUser;
import com.bdemo.common.AjaxResult;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class SuggestionController {
    private final SuggestionService service;
    public SuggestionController(SuggestionService service) { this.service=service; }

    // REQ-045~050: server owns priority, workflow, frozen source and template evidence.
    @GetMapping("/suggestions") public AjaxResult list(@RequestParam(required=false)String status,@RequestParam(required=false)String sourceType,
            @RequestParam(required=false)String ruleCode,@RequestParam(defaultValue="ALL")String zone,@RequestParam(required=false)String priorityBand,
            @RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize){return AjaxResult.success(service.list(status,sourceType,ruleCode,zone,priorityBand,pageNum,pageSize)).add("msg","suggestions");}
    @GetMapping("/suggestions/retrospective") public AjaxResult retrospective(@RequestParam(required=false)String month,@RequestParam(defaultValue="ALL")String zone,@RequestParam(required=false)String ruleCode){return AjaxResult.success(service.retrospective(month,zone,ruleCode)).add("msg","suggestion retrospective");}
    @GetMapping("/suggestions/{id}") public AjaxResult detail(@PathVariable long id){return AjaxResult.success(service.detail(id)).add("msg","suggestion detail");}
    @PostMapping("/suggestions") public AjaxResult create(@RequestBody Map<String,Object>body,@AuthenticationPrincipal AuthenticatedUser user){return AjaxResult.success(service.create(body,user.userName())).add("msg","suggestion created");}
    @PostMapping("/alerts/{eventId}/suggestions") public AjaxResult fromAlert(@PathVariable long eventId,@RequestBody Map<String,Object>body,@AuthenticationPrincipal AuthenticatedUser user){return AjaxResult.success(service.fromAlert(eventId,body,user.userName())).add("msg","alert converted to suggestion");}
    @PostMapping("/suggestions/{id}/transition") public AjaxResult transition(@PathVariable long id,@RequestBody Map<String,Object>body,@AuthenticationPrincipal AuthenticatedUser user){return AjaxResult.success(service.transition(id,body,user.userName())).add("msg","suggestion transitioned");}
    @PostMapping("/suggestions/{id}/activities") public AjaxResult activity(@PathVariable long id,@RequestBody Map<String,Object>body,@AuthenticationPrincipal AuthenticatedUser user){return AjaxResult.success(service.activity(id,body,user.userName())).add("msg","suggestion activity recorded");}
    @PostMapping("/suggestions/{id}/verification/generate") public AjaxResult verify(@PathVariable long id,@RequestBody Map<String,Object>body,@AuthenticationPrincipal AuthenticatedUser user){return AjaxResult.success(service.verify(id,body,user.userName())).add("msg","suggestion verification generated");}
    @GetMapping("/suggestion-templates") public AjaxResult templates(@RequestParam(required=false)String ruleCode,@RequestParam(required=false)String category,@RequestParam(required=false)String objectType,@RequestParam(required=false)Boolean enabled){return AjaxResult.success(service.templates(ruleCode,category,objectType,enabled)).add("msg","suggestion templates");}
    @PostMapping("/suggestion-templates") public AjaxResult createTemplate(@RequestBody Map<String,Object>body){return AjaxResult.success(service.createTemplate(body)).add("msg","suggestion template created");}
    @PutMapping("/suggestion-templates/{id}") public AjaxResult updateTemplate(@PathVariable long id,@RequestBody Map<String,Object>body){return AjaxResult.success(service.updateTemplate(id,body)).add("msg","suggestion template versioned");}
}
