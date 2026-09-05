package com.bproject.ehm.web;

import com.bproject.ehm.service.AiAssistantService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ehm/v1/assistant")
public class AssistantController {
    private final AiAssistantService assistant;

    public AssistantController(AiAssistantService assistant) {
        this.assistant = assistant;
    }

    @PostMapping("/chat")
    public AiAssistantService.AssistantResponse chat(@RequestBody ChatRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题不能为空");
        }
        return assistant.answer(request.message());
    }

    public record ChatRequest(String message) {
    }
}
