package com.redsegura.assetinventory.health;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.redsegura.assetinventory.config.SecurityConfig;
import com.redsegura.assetinventory.security.SecurityAuditLogger;
import com.redsegura.assetinventory.web.ProblemAccessDeniedHandler;
import com.redsegura.assetinventory.web.ProblemAuthenticationEntryPoint;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Casos HLTH-01/02/03: probes sin autenticación; readiness refleja el estado real de la BD. */
@WebMvcTest(HealthController.class)
@Import({
  SecurityConfig.class,
  ProblemAuthenticationEntryPoint.class,
  ProblemAccessDeniedHandler.class,
  SecurityAuditLogger.class
})
class HealthControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockBean private DataSource dataSource;

  /** HLTH-01: el liveness probe responde 200 {"status":"UP"} sin autenticación. */
  @Test
  void liveness_returnsUp() throws Exception {
    mockMvc
        .perform(get("/api/v1/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  /** HLTH-02: readiness con BD accesible -> 200 {"status":"UP"} sin autenticación. */
  @Test
  void readiness_dbUp_returnsUp() throws Exception {
    Connection connection = org.mockito.Mockito.mock(Connection.class);
    given(dataSource.getConnection()).willReturn(connection);
    given(connection.isValid(anyInt())).willReturn(true);

    mockMvc
        .perform(get("/api/v1/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  /** HLTH-03: readiness con BD caída -> 503 {"status":"DOWN"} (deja de recibir tráfico). */
  @Test
  void readiness_dbDown_returnsServiceUnavailable() throws Exception {
    given(dataSource.getConnection()).willThrow(new java.sql.SQLException("db down"));

    mockMvc
        .perform(get("/api/v1/health/readiness"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("DOWN"));
  }
}
