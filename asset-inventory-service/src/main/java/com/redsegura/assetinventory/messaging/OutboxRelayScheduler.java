package com.redsegura.assetinventory.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Dispara el {@link OutboxRelay} periódicamente. Se puede desactivar con {@code
 * redsegura.outbox.relay.enabled=false} (p. ej. en tests que invocan el relay manualmente); activo
 * por defecto en producción.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "redsegura.outbox.relay.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OutboxRelayScheduler {

  private final OutboxRelay outboxRelay;

  public OutboxRelayScheduler(OutboxRelay outboxRelay) {
    this.outboxRelay = outboxRelay;
  }

  @Scheduled(fixedDelayString = "${redsegura.outbox.relay.fixed-delay:2000}")
  void tick() {
    outboxRelay.publishPending();
  }
}
