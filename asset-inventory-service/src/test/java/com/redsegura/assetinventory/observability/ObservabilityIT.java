package com.redsegura.assetinventory.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.redsegura.assetinventory.AbstractIntegrationTest;
import com.redsegura.assetinventory.repository.DeviceRepository;
import com.redsegura.assetinventory.repository.OutboxRepository;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Observabilidad (RNF-15/16/17): métricas Prometheus reales, trazas y logs estructurados JSON.
 *
 * <p>{@code @AutoConfigureObservability} activa métricas y tracing en el test (Spring Boot los
 * desactiva por defecto en tests); en producción están activos sin necesidad de esta anotación.
 */
@AutoConfigureMockMvc
@AutoConfigureObservability
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private PrometheusMeterRegistry prometheusMeterRegistry;
  @Autowired private DeviceRepository deviceRepository;
  @Autowired private OutboxRepository outboxRepository;

  // Aísla el test: la BD (contenedor singleton) se comparte entre clases y otras suites dejan
  // datos.
  @BeforeEach
  void clean() {
    outboxRepository.deleteAll();
    deviceRepository.deleteAll();
  }

  /**
   * OBS-01: el registro Prometheus está cableado y produce métricas en formato de scrape (RNF-15).
   * Antes, {@code /actuator/prometheus} estaba expuesto pero sin registro (métrica muerta); ahora
   * la dependencia {@code micrometer-registry-prometheus} lo alimenta.
   */
  @Test
  void prometheusRegistry_producesMetrics() {
    String scrape = prometheusMeterRegistry.scrape();

    assertThat(scrape).contains("jvm_memory_used_bytes");
  }

  /**
   * OBS-02: los logs de la aplicación salen en JSON (RNF-17) con el nombre del servicio y el {@code
   * traceId} de la traza actual inyectado desde el MDC (RNF-16).
   */
  @Test
  void applicationLogs_areStructuredJsonWithTraceId(CapturedOutput output) throws Exception {
    // Una mutación genera una línea de SECURITY_AUDIT a INFO, dentro del span de la petición.
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(jwt().authorities(new SimpleGrantedAuthority("ADM")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"serialNumber\":\"OBS1\",\"hostname\":\"OBS-SW\",\"managementIpv4\":"
                        + "{\"address\":\"10.0.0.9\",\"prefixLength\":24},"
                        + "\"deviceType\":\"SWITCH\",\"criticality\":\"ALTA\"}"))
        .andExpect(status().isCreated());

    assertThat(output)
        .contains("\"service\":\"asset-inventory-service\"")
        .contains("\"logger\":\"SECURITY_AUDIT\"")
        .contains("\"traceId\"");
  }

  /**
   * OBS-03: métrica de dominio (RNF-15) — un alta efectiva incrementa `redsegura.devices.created`.
   */
  @Test
  void domainMetric_countsDeviceCreations() throws Exception {
    double before = prometheusMeterRegistry.get("redsegura.devices.created").counter().count();

    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(jwt().authorities(new SimpleGrantedAuthority("ADM")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"serialNumber\":\"MET1\",\"hostname\":\"MET-SW\",\"managementIpv4\":"
                        + "{\"address\":\"10.0.0.77\",\"prefixLength\":24},"
                        + "\"deviceType\":\"SWITCH\",\"criticality\":\"ALTA\"}"))
        .andExpect(status().isCreated());

    double after = prometheusMeterRegistry.get("redsegura.devices.created").counter().count();
    assertThat(after).isEqualTo(before + 1);
  }
}
