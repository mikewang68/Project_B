package com.bproject.ehm.assistant.ports;

import java.util.Optional;

public interface AssistantModelPort {
    Optional<String> answer(String message, String context);
}
