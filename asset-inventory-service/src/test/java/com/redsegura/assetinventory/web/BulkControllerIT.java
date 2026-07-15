package com.redsegura.assetinventory.web;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redsegura.assetinventory.AbstractIntegrationTest;
import com.redsegura.assetinventory.repository.DeviceRepository;
import com.redsegura.assetinventory.repository.ImportJobRepository;
import com.redsegura.assetinventory.repository.ImportJobResultRepository;
import com.redsegura.assetinventory.repository.OutboxRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Importación masiva (RF-04) de extremo a extremo: 202 + job asíncrono + RBAC + idempotencia. */
@AutoConfigureMockMvc
class BulkControllerIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DeviceRepository deviceRepository;
  @Autowired private ImportJobRepository jobRepository;
  @Autowired private ImportJobResultRepository resultRepository;
  @Autowired private OutboxRepository outboxRepository;

  @BeforeEach
  void clean() {
    resultRepository.deleteAll();
    jobRepository.deleteAll();
    outboxRepository.deleteAll();
    deviceRepository.deleteAll();
  }

  private static RequestPostProcessor admin() {
    return jwt().authorities(new SimpleGrantedAuthority("ADM"));
  }

  private static RequestPostProcessor auditor() {
    return jwt().authorities(new SimpleGrantedAuthority("AUD"));
  }

  private static String device(String serial, String hostname, String ip) {
    return """
        {"serialNumber":"%s","hostname":"%s","managementIpv4":{"address":"%s","prefixLength":24},"deviceType":"SWITCH","criticality":"ALTA"}
        """
        .formatted(serial, hostname, ip);
  }

  private static String batchOfTwo() {
    return "{\"devices\":["
        + device("S1", "SW1", "10.0.0.1")
        + ","
        + device("S2", "SW2", "10.0.0.2")
        + "]}";
  }

  private String pollUntilTerminal(String jobId) throws Exception {
    for (int i = 0; i < 100; i++) {
      String body =
          mockMvc
              .perform(get("/api/v1/devices/bulk/jobs/{id}", jobId).with(admin()))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      String jobStatus = objectMapper.readTree(body).get("status").asText();
      if ("COMPLETED".equals(jobStatus) || "FAILED".equals(jobStatus)) {
        return body;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("el job no alcanzó un estado terminal a tiempo");
  }

  /** RF-04: alta masiva -> 202 QUEUED con Location; el job asíncrono termina COMPLETED. */
  @Test
  void bulkImport_asAdmin_acceptedAndCompletes() throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/devices/bulk")
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(batchOfTwo()))
            .andExpect(status().isAccepted())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.status", is("QUEUED")))
            .andExpect(jsonPath("$.total", is(2)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String jobId = objectMapper.readTree(response).get("jobId").asText();

    String done = pollUntilTerminal(jobId);
    var node = objectMapper.readTree(done);
    org.assertj.core.api.Assertions.assertThat(node.get("status").asText()).isEqualTo("COMPLETED");
    org.assertj.core.api.Assertions.assertThat(node.get("succeeded").asInt()).isEqualTo(2);
    org.assertj.core.api.Assertions.assertThat(deviceRepository.count()).isEqualTo(2);
  }

  /** SEC: alta masiva con rol sin permiso (Auditor) -> 403. */
  @Test
  void bulkImport_asAuditor_forbidden() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices/bulk")
                .with(auditor())
                .contentType(MediaType.APPLICATION_JSON)
                .content(batchOfTwo()))
        .andExpect(status().isForbidden());
  }

  /** VAL: lote vacío -> 422 (viola minItems=1). */
  @Test
  void bulkImport_empty_returns422() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices/bulk")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"devices\":[]}"))
        .andExpect(status().isUnprocessableEntity());
  }

  /** ERR: job inexistente -> 404 problem+json. */
  @Test
  void getJob_unknown_returns404() throws Exception {
    mockMvc
        .perform(get("/api/v1/devices/bulk/jobs/{id}", UUID.randomUUID()).with(admin()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code", is("JOB_NOT_FOUND")));
  }

  /** RN9: misma Idempotency-Key + mismo cuerpo -> mismo job (replay). */
  @Test
  void bulkImport_sameIdempotencyKey_replaysSameJob() throws Exception {
    String first =
        mockMvc
            .perform(
                post("/api/v1/devices/bulk")
                    .with(admin())
                    .header("Idempotency-Key", "bulk-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(batchOfTwo()))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String firstJobId = objectMapper.readTree(first).get("jobId").asText();

    String second =
        mockMvc
            .perform(
                post("/api/v1/devices/bulk")
                    .with(admin())
                    .header("Idempotency-Key", "bulk-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(batchOfTwo()))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String secondJobId = objectMapper.readTree(second).get("jobId").asText();

    org.assertj.core.api.Assertions.assertThat(secondJobId).isEqualTo(firstJobId);
    // Drena el worker asíncrono antes de terminar el test (evita que siga corriendo tras el
    // @BeforeEach del siguiente, que borraría el job en pleno procesamiento).
    pollUntilTerminal(firstJobId);
  }
}
