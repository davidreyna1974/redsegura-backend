package com.redsegura.assetinventory.acceptance;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redsegura.assetinventory.repository.DeviceRepository;
import com.redsegura.assetinventory.repository.OutboxRepository;
import io.cucumber.java.Before;
import io.cucumber.java.es.Cuando;
import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import io.cucumber.java.es.Y;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Step definitions de las pruebas de aceptación (BDD). Traducen el lenguaje de negocio de los
 * escenarios Gherkin a llamadas HTTP sobre el servicio. El estado (rol, último resultado, id) es
 * por escenario (Cucumber instancia esta clase por escenario).
 */
public class DeviceAcceptanceSteps {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DeviceRepository deviceRepository;
  @Autowired private OutboxRepository outboxRepository;

  private String role = "ADM";
  private ResultActions result;
  private String deviceId;

  @Before
  public void clean() {
    outboxRepository.deleteAll();
    deviceRepository.deleteAll();
  }

  private RequestPostProcessor as(String roleCode) {
    return jwt().authorities(new SimpleGrantedAuthority(roleCode));
  }

  private static String bodyIpv4(String serial, String hostname, String ip) {
    return """
        {"serialNumber":"%s","hostname":"%s","managementIpv4":{"address":"%s","prefixLength":24},\
        "deviceType":"SWITCH","criticality":"ALTA"}"""
        .formatted(serial, hostname, ip);
  }

  private static String bodyIpv6(String serial, String hostname, String ipv6) {
    return """
        {"serialNumber":"%s","hostname":"%s","managementIpv6":{"address":"%s","prefixLength":64},\
        "deviceType":"SWITCH","criticality":"ALTA"}"""
        .formatted(serial, hostname, ipv6);
  }

  @Dado("que estoy autenticado como {string}")
  public void autenticadoComo(String rol) {
    role =
        switch (rol) {
          case "Administrador" -> "ADM";
          case "Operador" -> "OPE";
          case "Auditor" -> "AUD";
          default -> rol;
        };
  }

  @Cuando("registro un dispositivo con serie {string}, hostname {string} e IP {string}")
  public void registroConIp(String serial, String hostname, String ip) throws Exception {
    result =
        mockMvc.perform(
            post("/api/v1/devices")
                .with(as(role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyIpv4(serial, hostname, ip)));
  }

  @Cuando("registro un dispositivo con serie {string}, hostname {string} e IPv6 {string}")
  public void registroConIpv6(String serial, String hostname, String ipv6) throws Exception {
    result =
        mockMvc.perform(
            post("/api/v1/devices")
                .with(as(role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyIpv6(serial, hostname, ipv6)));
  }

  @Dado("he registrado un dispositivo con serie {string}, hostname {string} e IP {string}")
  public void heRegistrado(String serial, String hostname, String ip) throws Exception {
    registroConIp(serial, hostname, ip);
    seRegistraCorrectamente();
  }

  @Entonces("el dispositivo se registra correctamente")
  public void seRegistraCorrectamente() throws Exception {
    result.andExpect(status().isCreated());
    deviceId =
        objectMapper
            .readTree(result.andReturn().getResponse().getContentAsString())
            .get("id")
            .asText();
  }

  @Y("al consultarlo aparece en el inventario con hostname {string}")
  public void alConsultarloApareceConHostname(String hostname) throws Exception {
    mockMvc
        .perform(get("/api/v1/devices/{id}", deviceId).with(as(role)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hostname", is(hostname)));
  }

  @Y("su dirección de gestión IPv6 queda almacenada")
  public void suIpv6QuedaAlmacenada() throws Exception {
    mockMvc
        .perform(get("/api/v1/devices/{id}", deviceId).with(as(role)))
        .andExpect(jsonPath("$.managementIpv6.address").exists());
  }

  @Cuando("doy de baja el dispositivo")
  public void doyDeBaja() throws Exception {
    result =
        mockMvc.perform(
            delete("/api/v1/devices/{id}", deviceId).with(as(role)).header("If-Match", "\"0\""));
  }

  @Entonces("su estado pasa a {string}")
  public void suEstadoPasaA(String estado) throws Exception {
    result.andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/v1/devices/{id}", deviceId).with(as(role)))
        .andExpect(jsonPath("$.status", is(estado)));
  }

  @Cuando("busco dispositivos por hostname {string}")
  public void buscoPorHostname(String termino) throws Exception {
    result = mockMvc.perform(get("/api/v1/devices").with(as(role)).param("hostname", termino));
  }

  @Entonces("obtengo {int} dispositivo en los resultados")
  public void obtengoNResultados(int n) throws Exception {
    result.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements", is(n)));
  }

  @Entonces("la operación es rechazada por falta de permisos")
  public void rechazadaPorPermisos() throws Exception {
    result.andExpect(status().isForbidden());
  }

  @Cuando("un auditor consulta el dispositivo")
  public void unAuditorConsulta() throws Exception {
    result = mockMvc.perform(get("/api/v1/devices/{id}", deviceId).with(as("AUD")));
  }

  @Entonces("la dirección de gestión IPv4 aparece enmascarada como {string}")
  public void ipEnmascaradaComo(String enmascarada) throws Exception {
    result
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.managementIpv4.address", is(enmascarada)));
  }
}
