package com.bproject.ehm.assistant.adapter.in.web;

import com.bproject.ehm.assistant.application.AssistantApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.List;
import java.util.Map;
import com.bproject.ehm.assistant.ports.AssistantModelPort;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ehm/v1/assistant")
public class AssistantController {
    private final AssistantApplicationService assistant;

    public AssistantController(AssistantApplicationService assistant) {
        this.assistant = assistant;
    }

    @PostMapping("/chat")
    public AssistantApplicationService.AssistantResponse chat(@Valid @RequestBody ChatRequest request) {
        return assistant.answer(request.message(), request.sessionId(), request.deviceCode(), request.modelId());
    }

    @GetMapping("/status")
    public AssistantModelPort.ModelStatus status(@RequestParam(required=false) @Size(max=40) String modelId) { return assistant.status(modelId); }

    @GetMapping("/models")
    public Map<String,Object> models() { return Map.of("models",assistant.models(),"defaultModelId",assistant.defaultModelId()); }

    @PostMapping("/test")
    public Map<String, Object> test(@RequestParam(required=false) @Size(max=40) String modelId) { return assistant.test(modelId); }

    @GetMapping("/sessions/{id}")
    public List<AssistantApplicationService.Turn> history(@PathVariable String id) { return assistant.history(id); }

    @DeleteMapping("/sessions/{id}")
    public void clear(@PathVariable String id) { assistant.clear(id); }

    public record ChatRequest(@NotBlank(message = "问题不能为空") @Size(max=2000) String message,
                              @Size(max=64) String sessionId, @Size(max=80) String deviceCode, @Size(max=40) String modelId) {
    }
}
