package com.redsegura.assetinventory.service;

import com.redsegura.assetinventory.domain.Criticality;
import com.redsegura.assetinventory.domain.Device;
import com.redsegura.assetinventory.domain.DeviceStatus;
import com.redsegura.assetinventory.domain.DeviceType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** Construcción de filtros dinámicos para la búsqueda de dispositivos (§4.2 del contrato). */
public final class DeviceSpecifications {

  private DeviceSpecifications() {}

  /**
   * Carácter de escape para {@code LIKE} (evita que {@code %}/{@code _} del input actúen como
   * comodín).
   */
  private static final char LIKE_ESCAPE = '\\';

  /**
   * Filtros combinables (AND); los nulos se omiten. {@code hostname}, {@code vendor} y {@code
   * model} son búsqueda parcial insensible a mayúsculas; el resto es coincidencia exacta.
   * RN6/FLOW-01: si no se pide {@code status}, se excluyen los dados de baja.
   */
  public static Specification<Device> withFilters(
      String hostname,
      String mgmtIp,
      String serialNumber,
      DeviceType deviceType,
      String site,
      String rack,
      Criticality criticality,
      String vendor,
      String model,
      DeviceStatus status) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      if (hostname != null && !hostname.isBlank()) {
        predicates.add(accentInsensitiveContains(cb, root.get("hostname"), hostname));
      }
      if (mgmtIp != null && !mgmtIp.isBlank()) {
        // Coincidencia por dirección de gestión IPv4 o IPv6 (RF-05a). El valor ya viene
        // canonicalizado desde el servicio para igualar la forma almacenada.
        predicates.add(
            cb.or(
                cb.equal(root.get("managementIpv4").get("address"), mgmtIp),
                cb.equal(root.get("managementIpv6").get("address"), mgmtIp)));
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
      if (vendor != null && !vendor.isBlank()) {
        predicates.add(accentInsensitiveContains(cb, root.get("vendor"), vendor));
      }
      if (model != null && !model.isBlank()) {
        predicates.add(accentInsensitiveContains(cb, root.get("model"), model));
      }
      if (status != null) {
        predicates.add(cb.equal(root.get("status"), status));
      } else {
        predicates.add(cb.notEqual(root.get("status"), DeviceStatus.BAJA));
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }

  /**
   * Búsqueda parcial insensible a mayúsculas <b>y a acentos</b> (BSRCH-02): se aplica {@code
   * f_unaccent(lower(...))} a ambos lados, de modo que {@code galon} encuentra {@code Galón}.
   */
  private static Predicate accentInsensitiveContains(
      CriteriaBuilder cb, Path<String> path, String term) {
    Expression<String> field = cb.function("f_unaccent", String.class, cb.lower(path));
    Expression<String> pattern =
        cb.function("f_unaccent", String.class, cb.literal(containsPattern(term)));
    return cb.like(field, pattern, LIKE_ESCAPE);
  }

  /** Patrón {@code %valor%} en minúsculas, con los comodines de LIKE del input escapados. */
  private static String containsPattern(String raw) {
    String escaped =
        raw.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }
}
