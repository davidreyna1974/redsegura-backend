package com.redsegura.assetinventory.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redsegura.assetinventory.AbstractIntegrationTest;
import com.redsegura.assetinventory.generated.model.Criticality;
import com.redsegura.assetinventory.generated.model.DeviceCreateRequest;
import com.redsegura.assetinventory.generated.model.DeviceType;
import com.redsegura.assetinventory.repository.DeviceRepository;
import com.redsegura.assetinventory.repository.OutboxRepository;
import com.redsegura.assetinventory.service.DeviceService;
import com.redsegura.assetinventory.service.VersionedDevice;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Relay del outbox (RN11/ADR-04) contra un RabbitMQ real (Testcontainers): un evento pendiente se
 * publica en el topic exchange y llega a una cola bindeada con {@code asset.*}, y queda marcado como
 * publicado. El scheduler está desactivado (base); el relay se invoca manualmente.
 */
class OutboxRelayIT extends AbstractIntegrationTest {

  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-alpine");

  static {
    RABBIT.start();
  }

  private static final String TEST_QUEUE = "test.asset-events";

  @DynamicPropertySource
  static void rabbitProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
  }

  @Autowired private DeviceService service;
  @Autowired private OutboxRelay relay;
  @Autowired private OutboxRepository outboxRepository;
  @Autowired private DeviceRepository deviceRepository;
  @Autowired private RabbitAdmin rabbitAdmin;
  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void setup() {
    outboxRepository.deleteAll();
    deviceRepository.deleteAll();
    // Cola de prueba que "consume" cualquier evento asset.* del exchange común.
    TopicExchange exchange = new TopicExchange(RabbitConfig.EXCHANGE, true, false);
    Queue queue = new Queue(TEST_QUEUE, true);
    rabbitAdmin.declareExchange(exchange);
    rabbitAdmin.declareQueue(queue);
    rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("asset.*"));
    rabbitAdmin.purgeQueue(TEST_QUEUE, false);
  }

  /** RN11: el relay publica el evento pendiente al broker y lo marca como publicado. */
  @Test
  void publishPending_deliversAssetCreatedToBroker() throws Exception {
    VersionedDevice created =
        service.create(
            new DeviceCreateRequest("S1", "SW1", "10.0.0.1", DeviceType.SWITCH, Criticality.ALTA),
            null);

    int published = relay.publishPending();
    assertThat(published).isEqualTo(1);

    Message message = rabbitTemplate.receive(TEST_QUEUE, 5000);
    assertThat(message).as("el evento debe haber llegado a la cola").isNotNull();
    assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo("asset.created");
    assertThat(message.getMessageProperties().getMessageId()).isNotBlank();

    JsonNode envelope =
        objectMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
    assertThat(envelope.get("eventType").asText()).isEqualTo("asset.created");
    assertThat(envelope.get("payload").get("deviceId").asText())
        .isEqualTo(created.body().getId().toString());

    assertThat(outboxRepository.findAll().get(0).getPublishedAt()).isNotNull();
  }
}
