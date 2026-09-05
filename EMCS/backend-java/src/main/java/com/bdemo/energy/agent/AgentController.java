package com.bdemo.energy.agent;

import com.bdemo.common.AjaxResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/agent")
public class AgentController {
    private final AgentService service;public AgentController(AgentService service){this.service=service;}
    @PostMapping(value="/chat",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter chat(@RequestBody Map<String,Object>b)throws IOException{SseEmitter e=new SseEmitter(30_000L);e.send(SseEmitter.event().name("delta").data(Map.of("text",service.answer(String.valueOf(b.get("message"))))));e.send(SseEmitter.event().name("done").data(Map.of("elapsed_ms",1,"tool_calls",0)));e.complete();return e;}
    @GetMapping("/inspections") public AjaxResult list(@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize){return AjaxResult.success(service.list(pageNum,pageSize)).add("msg","inspections");}
    @GetMapping("/inspections/{id}") public AjaxResult detail(@PathVariable long id){return AjaxResult.success(service.detail(id)).add("msg","inspection detail");}
    @PostMapping("/inspections/run") public AjaxResult run(@RequestBody(required=false)Map<String,Object>b){return AjaxResult.success(Map.of("reportId",service.run(b==null?"manual":String.valueOf(b.getOrDefault("triggerType","manual"))))).add("msg","inspection completed");}
}
