package com.bproject.safety.module.ai;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.bproject.safety.common.error.GlobalExceptionHandler;
import com.bproject.safety.module.ai.service.AiEventService;
import com.bproject.safety.module.ai.web.AiEventController;
import com.bproject.safety.support.demo.DemoFeatureGuard;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiIngestValidationTest {
  @Test void rejectsOversizedPersistenceFieldsBeforeWritingAnyEvent() throws Exception {
    var service=mock(AiEventService.class);
    var mvc=MockMvcBuilders.standaloneSetup(new AiEventController(service,mock(DemoFeatureGuard.class)))
        .setControllerAdvice(new GlobalExceptionHandler()).build();
    for(String body:new String[]{"{\"eventType\":\""+"x".repeat(49)+"\"}",
        "{\"cameraCode\":\""+"x".repeat(33)+"\"}","{\"confidence\":101}","{\"durationSec\":-1}"})
      mvc.perform(post("/api/v1/ai-events/ingest").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }
}
