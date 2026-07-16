package com.redsegura.assetinventory.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.redsegura.assetinventory.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * RNF-27/ADR-17: la API es navegable en runtime. El contrato openapi.yaml se sirve como recurso
 * estático (fuente de verdad) y springdoc expone Swagger UI + /v3/api-docs. La doc es pública (sin
 * token), como confirma que estas peticiones sin autenticación devuelven 200.
 */
@AutoConfigureMockMvc
class ApiDocsIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void openapiContract_isServedAtRuntime_withoutAuth() throws Exception {
    mockMvc
        .perform(get("/openapi.yaml"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("openapi:")));
  }

  @Test
  void apiDocs_endpoint_isAvailable_withoutAuth() throws Exception {
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
  }
}
