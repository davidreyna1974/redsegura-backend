package com.redsegura.assetinventory.health;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Probes de salud diferenciados (ADR-10). Contrato: {@code GET /api/v1/health/liveness} y {@code
 * /readiness}, sin autenticación.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

  /** Segundos máximos para validar la conexión a la BD en el readiness. */
  private static final int DB_VALIDATION_TIMEOUT_SECONDS = 2;

  private final DataSource dataSource;

  public HealthController(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** Liveness: ¿el proceso vive? No depende de recursos externos. */
  @GetMapping("/liveness")
  public Map<String, String> liveness() {
    return Map.of("status", "UP");
  }

  /**
   * Readiness: ¿listo para tráfico? Verifica que PostgreSQL responde; si no, devuelve 503 para que
   * el balanceador/Kubernetes deje de enrutar tráfico a esta instancia hasta que se recupere.
   */
  @GetMapping("/readiness")
  public ResponseEntity<Map<String, String>> readiness() {
    if (isDatabaseReachable()) {
      return ResponseEntity.ok(Map.of("status", "UP"));
    }
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
  }

  private boolean isDatabaseReachable() {
    try (Connection connection = dataSource.getConnection()) {
      return connection.isValid(DB_VALIDATION_TIMEOUT_SECONDS);
    } catch (SQLException e) {
      return false;
    }
  }
}
