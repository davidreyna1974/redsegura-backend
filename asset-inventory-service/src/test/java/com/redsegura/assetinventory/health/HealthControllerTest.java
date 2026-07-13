package com.redsegura.assetinventory.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

/** Casos HLTH-01/02: los probes responden 200 UP sin autenticación. */
@WebMvcTest(HealthController.class)
class HealthControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void liveness_returnsUp() throws Exception {
    mockMvc
        .perform(get("/api/v1/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void readiness_returnsUp() throws Exception {
    mockMvc
        .perform(get("/api/v1/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }
}
