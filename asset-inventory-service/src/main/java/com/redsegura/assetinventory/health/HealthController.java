package com.redsegura.assetinventory.health;

import java.util.Map;
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

  /** Liveness: ¿el proceso vive? No depende de recursos externos. */
  @GetMapping("/liveness")
  public Map<String, String> liveness() {
    return Map.of("status", "UP");
  }

  /**
   * Readiness: ¿listo para tráfico? Verificará PostgreSQL y RabbitMQ cuando se añadan esas
   * dependencias; por ahora responde disponible.
   */
  @GetMapping("/readiness")
  public Map<String, String> readiness() {
    return Map.of("status", "UP");
  }
}
