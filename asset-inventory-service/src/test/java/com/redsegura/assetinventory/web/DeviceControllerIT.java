package com.redsegura.assetinventory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redsegura.assetinventory.AbstractIntegrationTest;
import com.redsegura.assetinventory.repository.DeviceRepository;
import com.redsegura.assetinventory.repository.IdempotencyRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Tests de extremo a extremo del controlador (stack completo + seguridad + PostgreSQL real). */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class DeviceControllerIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DeviceRepository repository;
  @Autowired private IdempotencyRepository idempotencyRepository;

  @BeforeEach
  void clean() {
    idempotencyRepository.deleteAll();
    repository.deleteAll();
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

  private static String body(String serial, String hostname, String ip) {
    return """
        {"serialNumber":"%s","hostname":"%s","managementIpv4":{"address":"%s","prefixLength":24},"deviceType":"SWITCH","criticality":"ALTA"}
        """
        .formatted(serial, hostname, ip);
  }

  /** CRUD-01: alta -> 201 con Location; luego GET -> 200. */
  @Test
  void createThenGet() throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/devices")
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("S1", "SW1", "10.0.0.1")))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.serialNumber", is("S1")))
            .andExpect(jsonPath("$.status", is("ACTIVO")))
            .andReturn()
            .getResponse()
            .getContentAsString();

    String id = objectMapper.readTree(response).get("id").asText();
    mockMvc
        .perform(get("/api/v1/devices/{id}", id).with(auditor()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hostname", is("SW1")));
  }

  /** SEC-01: alta con rol sin permiso (Auditor) -> 403 problem+json. */
  @Test
  void create_asAuditor_forbidden() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(auditor())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("S1", "SW1", "10.0.0.1")))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.code", is("ACCESS_DENIED")));
  }

  /** SEC-02: alta sin token -> 401 problem+json. */
  @Test
  void create_unauthenticated_returns401() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("S1", "SW1", "10.0.0.1")))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
  }

  /** SEC-06: una denegación (403) deja traza en el log de seguridad con su actor (ADR-11). */
  @Test
  void accessDenied_isAudited(CapturedOutput output) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(auditor())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("S1", "SW1", "10.0.0.1")))
        .andExpect(status().isForbidden());

    assertThat(output).contains("event=access_denied").contains("outcome=denied");
  }

  /** RBAC-01: listado como Auditor -> mgmtIp enmascarada (RN10/ADR-11). */
  @Test
  void list_asAuditor_masksMgmtIp() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("S1", "SW1", "10.0.0.1")))
        .andExpect(status().isCreated());

    mockMvc
        .perform(get("/api/v1/devices").with(auditor()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].managementIpv4.address", is("10.0.0.***")));
  }

  /** RBAC-02: listado como Operador -> mgmtIp en claro. */
  @Test
  void list_asOperator_showsMgmtIpClear() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("S1", "SW1", "10.0.0.1")))
        .andExpect(status().isCreated());

    mockMvc
        .perform(get("/api/v1/devices").with(operator()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].managementIpv4.address", is("10.0.0.1")));
  }

  /** RBAC-03/CYBER-02: detalle como Auditor -> mgmtIp enmascarada; el valor real no viaja. */
  @Test
  void getById_asAuditor_masksMgmtIp() throws Exception {
    String id = createAndGetId();

    String response =
        mockMvc
            .perform(get("/api/v1/devices/{id}", id).with(auditor()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.managementIpv4.address", is("10.0.0.***")))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // CYBER-02: el último octeto real no está presente en ninguna parte de la respuesta.
    assertThat(response).doesNotContain("10.0.0.1");
  }

  /** IP-02: alta solo con IPv6 (no canónica) -> 201 y se devuelve canonicalizada (RFC 5952). */
  @Test
  void create_withOnlyIpv6_canonicalizes() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"serialNumber\":\"S1\",\"hostname\":\"SW1\",\"managementIpv6\":"
                        + "{\"address\":\"2001:0DB8:0000:0000:0000:0000:0000:0011\",\"prefixLength\":64},"
                        + "\"deviceType\":\"SWITCH\",\"criticality\":\"ALTA\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.managementIpv6.address", is("2001:db8::11")))
        .andExpect(jsonPath("$.managementIpv4").doesNotExist());
  }

  /** IP-04: alta sin ninguna dirección de gestión -> 422 ADDRESS_INVALID. */
  @Test
  void create_withoutAnyAddress_returns422() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"serialNumber\":\"S1\",\"hostname\":\"SW1\","
                        + "\"deviceType\":\"SWITCH\",\"criticality\":\"ALTA\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code", is("ADDRESS_INVALID")));
  }

  /** IP-07/RBAC: detalle como Auditor -> IPv6 con host enmascarado; valor real no viaja. */
  @Test
  void getById_asAuditor_masksIpv6() throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/devices")
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"serialNumber\":\"S1\",\"hostname\":\"SW1\",\"managementIpv6\":"
                            + "{\"address\":\"2001:db8:acad:1::11\",\"prefixLength\":64},"
                            + "\"deviceType\":\"SWITCH\",\"criticality\":\"ALTA\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = objectMapper.readTree(response).get("id").asText();

    String masked =
        mockMvc
            .perform(get("/api/v1/devices/{id}", id).with(auditor()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.managementIpv6.address", is("2001:db8:acad:1::***")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(masked).doesNotContain("::11");
  }

  /** VAL-01/ERR-02: falta serialNumber -> 422 en formato problem+json. */
  @Test
  void create_invalid_returns422Problem() throws Exception {
    String invalid =
        "{\"hostname\":\"SW1\",\"managementIpv4\":{\"address\":\"10.0.0.1\",\"prefixLength\":24},"
            + "\"deviceType\":\"SWITCH\",\"criticality\":\"ALTA\"}";

    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalid))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
  }

  /** VAL-05: mgmtIp con formato inválido -> 422. */
  @Test
  void create_invalidIp_returns422() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("S1", "SW1", "999.1.1.1")))
        .andExpect(status().isUnprocessableEntity());
  }

  /** RN1: alta duplicada -> 409. */
  @Test
  void create_duplicate_returns409() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("DUP", "SW1", "10.0.0.1")))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("DUP", "SW2", "10.0.0.2")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code", is("DEVICE_ALREADY_EXISTS")));
  }

  /** ERR-01: id inexistente -> 404 problem+json. */
  @Test
  void get_missing_returns404() throws Exception {
    mockMvc
        .perform(get("/api/v1/devices/{id}", UUID.randomUUID()).with(auditor()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code", is("DEVICE_NOT_FOUND")));
  }

  /** CRUD-02: listado paginado (sobre estándar). */
  @Test
  void list_returnsPage() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("S1", "SW-CORE", "10.0.0.1")))
        .andExpect(status().isCreated());

    mockMvc
        .perform(get("/api/v1/devices").with(auditor()).param("hostname", "core"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(1)))
        .andExpect(jsonPath("$.content[0].hostname", is("SW-CORE")));
  }

  /** VAL/ERR: un campo de ordenación no permitido -> 400 problem+json (no 500). */
  @Test
  void list_withInvalidSortField_returns400() throws Exception {
    mockMvc
        .perform(get("/api/v1/devices").with(auditor()).param("sort", "password,asc"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.code", is("INVALID_REQUEST")));
  }

  /** El filtro por fabricante llega hasta la consulta (antes se ignoraba en el controlador). */
  @Test
  void list_filtersByVendor() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"serialNumber\":\"S1\",\"hostname\":\"SW1\",\"managementIpv4\":"
                        + "{\"address\":\"10.0.0.1\",\"prefixLength\":24},"
                        + "\"deviceType\":\"SWITCH\",\"criticality\":\"ALTA\",\"vendor\":\"Cisco\"}"))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"serialNumber\":\"S2\",\"hostname\":\"SW2\",\"managementIpv4\":"
                        + "{\"address\":\"10.0.0.2\",\"prefixLength\":24},"
                        + "\"deviceType\":\"SWITCH\",\"criticality\":\"ALTA\",\"vendor\":\"Juniper\"}"))
        .andExpect(status().isCreated());

    mockMvc
        .perform(get("/api/v1/devices").with(auditor()).param("fabricante", "cisco"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements", is(1)))
        .andExpect(jsonPath("$.content[0].serialNumber", is("S1")));
  }

  /** CRUD-06: baja lógica -> 204. */
  @Test
  void decommission_returns204() throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/devices")
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("S1", "SW1", "10.0.0.1")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = objectMapper.readTree(response).get("id").asText();

    // If-Match es obligatorio en el contrato (ADR-09); su lógica se implementa en un sub-hito
    // posterior. Se envía un valor cualquiera para satisfacer la cabecera requerida.
    mockMvc
        .perform(delete("/api/v1/devices/{id}", id).with(admin()).header("If-Match", "\"0\""))
        .andExpect(status().isNoContent());
  }

  /** RN9: reintento con la misma Idempotency-Key -> replay 201 (no 409), mismo dispositivo. */
  @Test
  void create_withSameIdempotencyKey_isReplayed() throws Exception {
    String b = body("S1", "SW1", "10.0.0.1");
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(b))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .header("Idempotency-Key", "k-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(b))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.serialNumber", is("S1")));
  }

  /** RN9: misma Idempotency-Key con cuerpo distinto -> 409 problem+json (no replay silencioso). */
  @Test
  void create_withSameIdempotencyKeyDifferentBody_returns409() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("S1", "SW1", "10.0.0.1")))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/api/v1/devices")
                .with(admin())
                .header("Idempotency-Key", "k-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("S2", "SW2", "10.0.0.2")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_CONFLICT")));
  }

  private String createAndGetId() throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/devices")
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("S1", "SW1", "10.0.0.1")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response).get("id").asText();
  }

  private static final MediaType MERGE_PATCH = MediaType.valueOf("application/merge-patch+json");

  /** CRUD-05: PATCH con If-Match correcto -> 200 y ETag incrementado. */
  @Test
  void patch_withCorrectIfMatch_returns200() throws Exception {
    String id = createAndGetId();

    mockMvc
        .perform(
            patch("/api/v1/devices/{id}", id)
                .with(admin())
                .header("If-Match", "\"0\"")
                .contentType(MERGE_PATCH)
                .content("{\"hostname\":\"SW1-NEW\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "\"1\""))
        .andExpect(jsonPath("$.hostname", is("SW1-NEW")));
  }

  /** FLOW-02/RN8: PATCH con If-Match desactualizado -> 412. */
  @Test
  void patch_withStaleIfMatch_returns412() throws Exception {
    String id = createAndGetId();

    mockMvc
        .perform(
            patch("/api/v1/devices/{id}", id)
                .with(admin())
                .header("If-Match", "\"999\"")
                .contentType(MERGE_PATCH)
                .content("{\"hostname\":\"SW1-NEW\"}"))
        .andExpect(status().isPreconditionFailed())
        .andExpect(jsonPath("$.code", is("PRECONDITION_FAILED")));
  }

  /** FLOW-03/RN8: PATCH sin If-Match -> 428. */
  @Test
  void patch_withoutIfMatch_returns428() throws Exception {
    String id = createAndGetId();

    mockMvc
        .perform(
            patch("/api/v1/devices/{id}", id)
                .with(admin())
                .contentType(MERGE_PATCH)
                .content("{\"hostname\":\"SW1-NEW\"}"))
        .andExpect(status().isPreconditionRequired())
        .andExpect(jsonPath("$.code", is("PRECONDITION_REQUIRED")));
  }
}
