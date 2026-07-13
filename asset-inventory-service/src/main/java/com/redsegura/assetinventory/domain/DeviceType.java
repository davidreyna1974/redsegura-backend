package com.redsegura.assetinventory.domain;

/**
 * Tipo/rol del dispositivo (estándar CMDB/CIM). Otros servicios lo usan para decidir playbooks y
 * escaneos (p. ej. compliance-audit, scan-orchestrator).
 */
public enum DeviceType {
  ROUTER,
  SWITCH,
  FIREWALL,
  HOST,
  ACCESS_POINT,
  OTHER
}
