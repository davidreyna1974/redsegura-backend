package com.redsegura.assetinventory.acceptance;

import com.redsegura.assetinventory.AbstractIntegrationTest;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * Vincula Cucumber con el contexto de Spring Boot: reutiliza {@link AbstractIntegrationTest}
 * (PostgreSQL real vía Testcontainers, relay del outbox desactivado) y habilita MockMvc para que
 * los step definitions ejerzan la API sobre el stack completo.
 */
@CucumberContextConfiguration
@AutoConfigureMockMvc
class CucumberSpringConfiguration extends AbstractIntegrationTest {}
