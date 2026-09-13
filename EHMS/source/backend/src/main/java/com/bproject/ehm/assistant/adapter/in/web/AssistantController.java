package com.bproject.ehm.assistant.adapter.in.web;

import com.bproject.ehm.assistant.application.AssistantApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
        return assistant.answer(request.message());
    }

    public record ChatRequest(@NotBlank(message = "问题不能为空") String message) {
    }
}
