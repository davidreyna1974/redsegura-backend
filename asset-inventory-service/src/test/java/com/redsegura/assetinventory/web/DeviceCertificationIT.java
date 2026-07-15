package com.redsegura.assetinventory.web;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redsegura.assetinventory.AbstractIntegrationTest;
import com.redsegura.assetinventory.repository.DeviceRepository;
import com.redsegura.assetinventory.repository.OutboxRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Casos de certificación QA (ronda R1) que faltaban por cubrir: PUT completo, RBAC de edición/baja,
 * precondiciones, validaciones, búsqueda multi-filtro/vacía, y verificaciones de ciberseguridad
 * (inyección, no fuga de stack trace). Complementa {@link DeviceControllerIT}.
 */
@AutoConfigureMockMvc
class DeviceCertificationIT extends AbstractIntegrationTest {

  private static final MediaType MERGE_PATCH = MediaType.valueOf("application/merge-patch+json");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DeviceRepository deviceRepository;
  @Autowired private OutboxRepository outboxRepository;

  @BeforeEach
  void clean() {
    outboxRepository.deleteAll();
    deviceRepository.deleteAll();
  }

  private static RequestPostProcessor admin() {
    return jwt().authorities(new SimpleGrantedAuthority("ADM"));
  }

  private static RequestPostProcessor operator() {
    return jwt().authorities(new SimpleGrantedAuthority("OPE"));
  }

  private static RequestPostProcessor auditor() {
    return jwt().authorities(new SimpleGrantedAuthority("AUD"));
  }

  private static String createBody(String serial, String hostname, String ip) {
    return """
        {"serialNumber":"%s","hostname":"%s","managementIpv4":{"address":"%s","prefixLength":24},\
        "deviceType":"SWITCH","criticality":"ALTA"}"""
        .formatted(serial, hostname, ip);
  }

  private String createDevice(String serial, String hostname, String ip) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/devices")
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(serial, hostname, ip)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response).get("id").asText();
  }

  // ---- CRUD-04: PUT completo con If-Match ----
  @Test
  void replace_withIfMatch_returns200AndBumpsEtag() throws Exception {
    String id = createDevice("S1", "SW1", "10.0.0.1");

    mockMvc
        .perform(
            put("/api/v1/devices/{id}", id)
                .with(admin())
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"hostname":"SW1-PUT","managementIpv4":{"address":"10.0.0.50","prefixLength":24},\
                    "deviceType":"ROUTER","criticality":"MEDIA"}"""))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "\"1\""))
        .andExpect(jsonPath("$.hostname", is("SW1-PUT")))
        .andExpect(jsonPath("$.managementIpv4.address", is("10.0.0.50")));
  }

  // ---- SEC-03 / SEC-04: RBAC de edición y baja ----
  @Test
  void patch_asAuditor_forbidden() throws Exception {
    String id = createDevice("S1", "SW1", "10.0.0.1");

    mockMvc
        .perform(
            patch("/api/v1/devices/{id}", id)
                .with(auditor())
                .header("If-Match", "\"0\"")
                .contentType(MERGE_PATCH)
                .content("{\"hostname\":\"X\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
  }

  @Test
  void delete_asOperator_forbidden() throws Exception {
    String id = createDevice("S1", "SW1", "10.0.0.1");

    mockMvc
        .perform(delete("/api/v1/devices/{id}", id).with(operator()).header("If-Match", "\"0\""))
        .andExpect(status().isForbidden());
  }

  // ---- FLOW-04: baja sin If-Match -> 428 ----
  @Test
  void delete_withoutIfMatch_returns428() throws Exception {
    String id = createDevice("S1", "SW1", "10.0.0.1");

    mockMvc
        .perform(delete("/api/v1/devices/{id}", id).with(admin()))
        .andExpect(status().isPreconditionRequired())
        .andExpect(jsonPath("$.code", is("PRECONDITION_REQUIRED")));
  }

  // ---- VAL-02 / VAL-06: validaciones de cuerpo -> 422 ----
  @Test
  void create_missingHostname_returns422() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"serialNumber\":\"S1\",\"managementIpv4\":{\"address\":\"10.0.0.1\","
                        + "\"prefixLength\":24},\"deviceType\":\"SWITCH\",\"criticality\":\"ALTA\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
  }

  @Test
  void create_rackUnitOutOfRange_returns422() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"serialNumber\":\"S1\",\"hostname\":\"SW1\",\"managementIpv4\":"
                        + "{\"address\":\"10.0.0.1\",\"prefixLength\":24},\"deviceType\":\"SWITCH\","
                        + "\"criticality\":\"ALTA\",\"location\":{\"rackUnit\":99}}"))
        .andExpect(status().isUnprocessableEntity());
  }

  // ---- VAL-03 / VAL-04: enum inválido -> 400 (JSON no deserializable) ----
  @Test
  void create_invalidCriticalityEnum_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"serialNumber\":\"S1\",\"hostname\":\"SW1\",\"managementIpv4\":"
                        + "{\"address\":\"10.0.0.1\",\"prefixLength\":24},\"deviceType\":\"SWITCH\","
                        + "\"criticality\":\"URGENTE\"}"))
        .andExpect(status().isBadRequest());
  }

  // ---- VAL-04: deviceType fuera de enum -> 400 ----
  @Test
  void create_invalidDeviceTypeEnum_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"serialNumber\":\"S1\",\"hostname\":\"SW1\",\"managementIpv4\":"
                        + "{\"address\":\"10.0.0.1\",\"prefixLength\":24},\"deviceType\":\"MAINFRAME\","
                        + "\"criticality\":\"ALTA\"}"))
        .andExpect(status().isBadRequest());
  }

  // ---- RN-02: hostname duplicado -> 409 ----
  @Test
  void create_duplicateHostname_returns409() throws Exception {
    createDevice("S1", "SW-DUP", "10.0.0.1");

    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("S2", "SW-DUP", "10.0.0.2")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code", is("DEVICE_ALREADY_EXISTS")));
  }

  // ---- VAL-07: tamaño de página por encima del máximo del contrato (100) -> 422 ----
  @Test
  void list_sizeAboveMax_returns422() throws Exception {
    mockMvc
        .perform(get("/api/v1/devices").with(admin()).param("size", "1000"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
  }

  /** VAL-07b: dentro del máximo, la página respeta el tamaño pedido. */
  @Test
  void list_sizeWithinMax_isHonored() throws Exception {
    createDevice("S1", "SW1", "10.0.0.1");

    mockMvc
        .perform(get("/api/v1/devices").with(admin()).param("size", "50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size", is(50)));
  }

  // ---- EMPTY-01 / BSRCH-04: inventario vacío y búsqueda sin coincidencias ----
  @Test
  void list_emptyInventory_returnsEmptyPage() throws Exception {
    mockMvc
        .perform(get("/api/v1/devices").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(0)))
        .andExpect(jsonPath("$.content", org.hamcrest.Matchers.hasSize(0)));
  }

  @Test
  void list_noMatch_returnsEmptyPage() throws Exception {
    createDevice("S1", "SW-CORE", "10.0.0.1");

    mockMvc
        .perform(get("/api/v1/devices").with(admin()).param("hostname", "inexistente"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(0)));
  }

  // ---- BSRCH-03: filtros combinados (AND) ----
  @Test
  void list_combinedFilters_appliedAsAnd() throws Exception {
    createDevice("S1", "SW-CORE", "10.0.0.1");
    createDevice("S2", "RT-EDGE", "10.0.0.2");

    mockMvc
        .perform(
            get("/api/v1/devices")
                .with(admin())
                .param("deviceType", "SWITCH")
                .param("criticidad", "ALTA"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(2)));
  }

  // ---- IP-08: filtro por dirección IPv6 ----
  @Test
  void list_filtersByIpv6Address() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"serialNumber\":\"S1\",\"hostname\":\"SW1\",\"managementIpv6\":"
                        + "{\"address\":\"2001:db8::1\",\"prefixLength\":64},\"deviceType\":\"SWITCH\","
                        + "\"criticality\":\"ALTA\"}"))
        .andExpect(status().isCreated());
    createDevice("S2", "SW2", "10.0.0.2");

    // Búsqueda con forma no canónica: debe canonicalizar y coincidir.
    mockMvc
        .perform(get("/api/v1/devices").with(admin()).param("mgmtIp", "2001:0DB8::1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(1)))
        .andExpect(jsonPath("$.content[0].serialNumber", is("S1")));
  }

  // ---- CYBER-01: inyección en filtros (consulta parametrizada) ----
  @Test
  void list_injectionInFilter_isSafe() throws Exception {
    createDevice("S1", "SW1", "10.0.0.1");

    mockMvc
        .perform(get("/api/v1/devices").with(admin()).param("hostname", "' OR '1'='1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(0)));
  }

  // ---- CYBER-03 / ERR-03: errores sin fuga de internos; JSON malformado -> 400 ----
  @Test
  void error_doesNotLeakStackTraceOrInternals() throws Exception {
    String body =
        mockMvc
            .perform(get("/api/v1/devices/{id}", java.util.UUID.randomUUID()).with(admin()))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // RNF-09: sin stack trace, clases internas ni detalles de SQL/persistencia. (El campo
    // `instance`
    // sí lleva la URI del recurso —p. ej. /api/v1/devices/{id}— y eso es legítimo.)
    Assertions.assertThat(body)
        .doesNotContain("Exception")
        .doesNotContain("at com.redsegura")
        .doesNotContain("org.hibernate")
        .doesNotContainIgnoringCase("select ")
        .doesNotContainIgnoringCase("nested exception");
  }

  @Test
  void create_malformedJson_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serialNumber\":\"S1\",")) // JSON truncado
        .andExpect(status().isBadRequest());
  }
}
