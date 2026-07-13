package com.redsegura.assetinventory.service;

import com.redsegura.assetinventory.domain.Criticality;
import com.redsegura.assetinventory.domain.Device;
import com.redsegura.assetinventory.domain.DeviceStatus;
import com.redsegura.assetinventory.domain.DeviceType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** Construcción de filtros dinámicos para la búsqueda de dispositivos (§4.2 del contrato). */
public final class DeviceSpecifications {

  private DeviceSpecifications() {}

  /**
   * Filtros combinables (AND); los nulos se omiten. {@code hostname} es búsqueda parcial insensible
   * a mayúsculas. RN6/FLOW-01: si no se pide {@code status}, se excluyen los dados de baja.
   */
  public static Specification<Device> withFilters(
      String hostname,
      String mgmtIp,
      String serialNumber,
      DeviceType deviceType,
      String site,
      String rack,
      Criticality criticality,
      DeviceStatus status) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      if (hostname != null && !hostname.isBlank()) {
        predicates.add(cb.like(cb.lower(root.get("hostname")), "%" + hostname.toLowerCase() + "%"));
      }
      if (mgmtIp != null && !mgmtIp.isBlank()) {
        predicates.add(cb.equal(root.get("mgmtIp"), mgmtIp));
      }
      if (serialNumber != null && !serialNumber.isBlank()) {
        predicates.add(cb.equal(root.get("serialNumber"), serialNumber));
      }
      if (deviceType != null) {
        predicates.add(cb.equal(root.get("deviceType"), deviceType));
      }
      if (site != null && !site.isBlank()) {
        predicates.add(cb.equal(root.get("location").get("site"), site));
      }
      if (rack != null && !rack.isBlank()) {
        predicates.add(cb.equal(root.get("location").get("rack"), rack));
      }
      if (criticality != null) {
        predicates.add(cb.equal(root.get("criticality"), criticality));
      }
      if (status != null) {
        predicates.add(cb.equal(root.get("status"), status));
      } else {
        predicates.add(cb.notEqual(root.get("status"), DeviceStatus.BAJA));
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }
}
