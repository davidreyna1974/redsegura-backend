package com.redsegura.assetinventory.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología de mensajería (catálogo de eventos §3). Este servicio es <b>productor</b>: declara el
 * {@code topic exchange} común {@code redsegura.events} (durable) de forma idempotente y publica
 * con routing keys {@code asset.*}. Las colas/bindings las declaran los servicios consumidores.
 */
@Configuration
public class RabbitConfig {

  /** Exchange común del sistema (durable). */
  public static final String EXCHANGE = "redsegura.events";

  public static final String ROUTING_ASSET_CREATED = "asset.created";
  public static final String ROUTING_ASSET_UPDATED = "asset.updated";
  public static final String ROUTING_ASSET_DECOMMISSIONED = "asset.decommissioned";

  @Bean
  TopicExchange redseguraEventsExchange() {
    // durable=true, autoDelete=false: sobrevive al reinicio del broker (RNF-E2).
    return new TopicExchange(EXCHANGE, true, false);
  }
}
