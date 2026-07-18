package com.redsegura.assetinventory.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.redsegura.assetinventory.domain.OutboxEvent;
import com.redsegura.assetinventory.repository.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * RNF-30/ADR-15 — camino negativo (garantía de entrega): si el broker <b>no confirma</b> la
 * publicación (nack o timeout de los publisher confirms), el relay debe propagar el fallo y
 * <b>no</b> marcar los eventos como publicados, para que sigan pendientes y se reintenten. Sin este
 * comportamiento, un evento podría perderse (marcado publicado sin haber salido de verdad).
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayConfirmFailureTest {

  @Mock private OutboxRepository outboxRepository;
  @Mock private RabbitTemplate rabbitTemplate;

  private OutboxRelay relay;

  @BeforeEach
  void setup() {
    relay = new OutboxRelay(outboxRepository, rabbitTemplate, 5000L, new SimpleMeterRegistry());
  }

  @Test
  void unconfirmedPublish_doesNotMarkEventPublished() {
    OutboxEvent event =
        new OutboxEvent(UUID.randomUUID(), "Device", UUID.randomUUID(), "asset.created", "{}");
    given(outboxRepository.lockPendingBatch()).willReturn(List.of(event));
    given(rabbitTemplate.invoke(any()))
        .willThrow(new AmqpException("el broker no confirmó el lote"));

    assertThatThrownBy(() -> relay.publishPending()).isInstanceOf(AmqpException.class);

    assertThat(event.getPublishedAt())
        .as("un evento sin ACK del broker NO debe marcarse publicado (se reintenta)")
        .isNull();
  }
}
