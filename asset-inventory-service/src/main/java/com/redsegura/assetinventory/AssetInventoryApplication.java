package com.redsegura.assetinventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Punto de entrada del microservicio de inventario de activos (fuente de verdad, RF-01..05). */
@SpringBootApplication
public class AssetInventoryApplication {

  public static void main(String[] args) {
    SpringApplication.run(AssetInventoryApplication.class, args);
  }
}
