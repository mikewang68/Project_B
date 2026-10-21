package com.bproject.trust.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MissingParameterTest {
  @RestController
  static class RequiredQuery {
    @GetMapping("/required")
    String read(@RequestParam("kind") String kind) { return kind; }
  }

  @Test
  void missingQueryReturnsJsonWithoutErrorDispatch() throws Exception {
    MockMvcBuilders.standaloneSetup(new RequiredQuery())
        .setControllerAdvice(new ErrorHandler()).build()
        .perform(get("/required"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$.message").exists());
  }
}
