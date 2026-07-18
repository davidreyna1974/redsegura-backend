package com.redsegura.assetinventory.messaging;

import com.redsegura.assetinventory.domain.OutboxEvent;
import com.redsegura.assetinventory.repository.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relay del transactional outbox (RN11/ADR-04, endurecido RNF-30/ADR-15): toma un lote de eventos
 * pendientes con {@code FOR UPDATE SKIP LOCKED} (seguro con varias réplicas), los publica al {@code
 * topic exchange} común y los marca como publicados <b>solo tras el ACK del broker</b> (publisher
 * confirms). Semántica <b>at-least-once</b>: si el broker no confirma, la transacción revierte y el
 * lote sigue pendiente para el siguiente tick; los consumidores deduplican por {@code eventId}. Los
 * mensajes son persistentes.
 */
@Component
public class OutboxRelay {

  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxRepository outboxRepository;
  private final RabbitTemplate rabbitTemplate;
  private final long confirmTimeoutMs;
  private final Counter eventsPublished;

  public OutboxRelay(
      OutboxRepository outboxRepository,
      RabbitTemplate rabbitTemplate,
      @Value("${redsegura.outbox.relay.confirm-timeout-ms:5000}") long confirmTimeoutMs,
      MeterRegistry meterRegistry) {
    this.outboxRepository = outboxRepository;
    this.rabbitTemplate = rabbitTemplate;
    this.confirmTimeoutMs = confirmTimeoutMs;
    // Métrica de dominio (RNF-15): eventos de dominio confirmados por el broker.
    this.eventsPublished =
        Counter.builder("redsegura.outbox.events.published")
            .description("Eventos de dominio publicados al broker (confirmados)")
            .register(meterRegistry);
  }

  /**
   * Publica el lote de eventos pendientes y los marca como publicados. Devuelve cuántos publicó.
   * Corre en transacción: publica el lote y espera los <b>publisher confirms</b>; si el broker no
   * confirma (nack o timeout), lanza y la transacción revierte → el lote sigue pendiente y se
   * reintenta. Solo se marca publicado lo que el broker aceptó.
   */
  @Transactional
  public int publishPending() {
    List<OutboxEvent> pending = outboxRepository.lockPendingBatch();
    if (pending.isEmpty()) {
      return 0;
    }
    rabbitTemplate.invoke(
        operations -> {
          for (OutboxEvent event : pending) {
            operations.send(RabbitConfig.EXCHANGE, event.getEventType(), toMessage(event));
          }
          operations.waitForConfirmsOrDie(confirmTimeoutMs);
          return null;
        });
    pending.forEach(OutboxEvent::markPublished);
    eventsPublished.increment(pending.size());
    LOG.info("event=outbox_published count={}", pending.size());
    return pending.size();
  }

  private static Message toMessage(OutboxEvent event) {
    return MessageBuilder.withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
        .setContentEncoding(StandardCharsets.UTF_8.name())
        .setMessageId(event.getEventId().toString())
        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
        .build();
  }
}
