package com.redsegura.assetinventory.messaging;

import com.redsegura.assetinventory.domain.OutboxEvent;
import com.redsegura.assetinventory.repository.OutboxRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relay del transactional outbox (RN11/ADR-04): lee los eventos pendientes y los publica al {@code
 * topic exchange} común, marcándolos como publicados. Semántica <b>at-least-once</b> (si la
 * publicación falla, el evento sigue pendiente y se reintenta; los consumidores deduplican por
 * {@code eventId}, RNF-E1). Los mensajes son persistentes (RNF-E2).
 */
@Component
public class OutboxRelay {

  private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxRepository outboxRepository;
  private final RabbitTemplate rabbitTemplate;

  public OutboxRelay(OutboxRepository outboxRepository, RabbitTemplate rabbitTemplate) {
    this.outboxRepository = outboxRepository;
    this.rabbitTemplate = rabbitTemplate;
  }

  /**
   * Publica el lote de eventos pendientes y los marca como publicados. Devuelve cuántos publicó.
   * Corre en transacción: si una publicación falla, se revierte el marcado del lote y se reintenta.
   */
  @Transactional
  public int publishPending() {
    List<OutboxEvent> pending = outboxRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
    for (OutboxEvent event : pending) {
      rabbitTemplate.send(RabbitConfig.EXCHANGE, event.getEventType(), toMessage(event));
      event.markPublished();
    }
    if (!pending.isEmpty()) {
      LOG.info("event=outbox_published count={}", pending.size());
    }
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
