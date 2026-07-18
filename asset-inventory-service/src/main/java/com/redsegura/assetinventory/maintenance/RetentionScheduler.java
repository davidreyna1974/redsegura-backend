package com.redsegura.assetinventory.maintenance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Dispara la {@link RetentionCleanup} periódicamente (por defecto, de madrugada). Se puede
 * desactivar con {@code redsegura.retention.enabled=false} (p. ej. en tests, que invocan la purga
 * manualmente). Con varias réplicas, un borrado concurrente de filas antiguas es idempotente (no
 * hay riesgo de doble publicación como en el relay), así que no requiere coordinación adicional.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "redsegura.retention.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RetentionScheduler {

  private final RetentionCleanup retentionCleanup;

  public RetentionScheduler(RetentionCleanup retentionCleanup) {
    this.retentionCleanup = retentionCleanup;
  }

  @Scheduled(cron = "${redsegura.retention.cron:0 30 3 * * *}")
  void tick() {
    retentionCleanup.purge();
  }
}
