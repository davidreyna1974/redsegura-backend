package com.redsegura.assetinventory.acceptance;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * Runner de las pruebas de aceptación (BDD / Gherkin). Ejecuta los escenarios en lenguaje de
 * negocio de {@code src/test/resources/features} contra el stack real (Testcontainers). Son la base
 * automatizada de la UAT: el cliente lee/aprueba los {@code .feature} y aquí se verifican.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.redsegura.assetinventory.acceptance")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty")
class AcceptanceTest {}
