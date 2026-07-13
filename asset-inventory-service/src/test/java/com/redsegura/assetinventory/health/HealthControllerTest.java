package com.redsegura.assetinventory.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.redsegura.assetinventory.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Casos HLTH-01/02: los probes responden 200 UP sin autenticación (SecurityConfig los permite). */
@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
class HealthControllerTest {

  @Autowired private MockMvc mockMvc;

  /** HLTH-01: el liveness probe responde 200 {"status":"UP"} sin autenticación. */
  @Test
  void liveness_returnsUp() throws Exception {
    mockMvc
        .perform(get("/api/v1/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  /** HLTH-02: el readiness probe responde 200 {"status":"UP"} sin autenticación. */
  @Test
  void readiness_returnsUp() throws Exception {
    mockMvc
        .perform(get("/api/v1/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }
}
