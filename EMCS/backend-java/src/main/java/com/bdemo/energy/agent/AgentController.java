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
    // REQ-AGENT-TBD: 保持 delta/error/done 事件契约，错误消息不包含供应商密钥。
    @PostMapping(value="/chat", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody Map<String,Object> body) throws IOException {
        SseEmitter emitter = new SseEmitter(310_000L);
        long started = System.currentTimeMillis();
        try {
            Object value = body.get("message");
            if (!(value instanceof String message) || message.isBlank() || message.length() > 8000)
                throw new IllegalArgumentException("请输入 1–8000 字的问题");
            String answer = service.answer(message);
            emitter.send(SseEmitter.event().name("delta").data(Map.of("text", answer)));
            emitter.send(SseEmitter.event().name("done").data(Map.of("elapsed_ms", System.currentTimeMillis()-started, "tool_calls", 0)));
        } catch (com.bdemo.common.BusinessException | IllegalArgumentException error) {
            emitter.send(SseEmitter.event().name("error").data(Map.of("message", error.getMessage())));
        }
        emitter.complete();
        return emitter;
    }
    @GetMapping("/inspections") public AjaxResult list(@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize){return AjaxResult.success(service.list(pageNum,pageSize)).add("msg","inspections");}
    @GetMapping("/inspections/{id}") public AjaxResult detail(@PathVariable long id){return AjaxResult.success(service.detail(id)).add("msg","inspection detail");}
    @PostMapping("/inspections/run") public AjaxResult run(@RequestBody(required=false)Map<String,Object>b){return AjaxResult.success(Map.of("reportId",service.run(b==null?"manual":String.valueOf(b.getOrDefault("triggerType","manual"))))).add("msg","inspection completed");}
}
