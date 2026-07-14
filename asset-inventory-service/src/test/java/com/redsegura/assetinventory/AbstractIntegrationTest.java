package com.redsegura.assetinventory;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base de tests de integración: levanta un PostgreSQL real con Testcontainers (estándar del
 * proyecto: sin mocks de BD), compartido entre clases como singleton para no arrancar un contenedor
 * por clase. Flyway aplica las migraciones sobre ese contenedor.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    // Por defecto se desactiva el relay del outbox (no requiere RabbitMQ); los tests de mensajería
    // que lo necesitan lo invocan manualmente contra un broker de Testcontainers.
    registry.add("redsegura.outbox.relay.enabled", () -> "false");
  }
}
