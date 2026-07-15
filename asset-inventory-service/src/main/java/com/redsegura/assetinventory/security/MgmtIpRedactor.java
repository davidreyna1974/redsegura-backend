package com.redsegura.assetinventory.security;

import com.redsegura.assetinventory.generated.model.Device;
import com.redsegura.assetinventory.generated.model.Ipv4Address;
import com.redsegura.assetinventory.generated.model.Ipv6Address;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Redacción de las direcciones de gestión por rol (RN10/ADR-11/RF-05a). Para el rol Auditor se
 * enmascara la <b>porción de host</b> (IPv4 {@code 10.0.0.11} → {@code 10.0.0.***}; IPv6 {@code
 * 2001:db8:acad:1::11} → {@code 2001:db8:acad:1::***}) tanto de la dirección como de su gateway;
 * Administrador y Operador las ven en claro.
 *
 * <p>Es una redacción <b>server-side</b>: se aplica sobre el DTO antes de serializar, de modo que
 * el valor real <b>no viaja</b> en la respuesta (CYBER-02).
 */
@Component
public class MgmtIpRedactor {

  private static final String MASK = "***";
  private static final String ROLE_ADMIN = "ADM";
  private static final String ROLE_OPERATOR = "OPE";
  private static final String ROLE_AUDITOR = "AUD";

  /**
   * Enmascara las direcciones de gestión del dispositivo si el usuario actual debe verlas
   * redactadas.
   */
  public void maybeRedact(Device device) {
    if (device != null && shouldRedact()) {
      redact(device);
    }
  }

  /**
   * Igual que {@link #maybeRedact(Device)} para cada elemento de una lista (evita re-evaluar rol).
   */
  public void maybeRedact(List<Device> devices) {
    if (devices != null && shouldRedact()) {
      devices.forEach(this::redact);
    }
  }

  private void redact(Device device) {
    Ipv4Address v4 = device.getManagementIpv4();
    if (v4 != null) {
      v4.setAddress(maskHost(v4.getAddress()));
      v4.setGateway(maskHost(v4.getGateway()));
    }
    Ipv6Address v6 = device.getManagementIpv6();
    if (v6 != null) {
      v6.setAddress(maskHost(v6.getAddress()));
      v6.setGateway(maskHost(v6.getGateway()));
    }
  }

  /** Redacta solo para Auditor: si el usuario tiene rol Administrador u Operador, ve en claro. */
  private boolean shouldRedact() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null) {
      return false;
    }
    Set<String> roles =
        auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());
    boolean privileged = roles.contains(ROLE_ADMIN) || roles.contains(ROLE_OPERATOR);
    return !privileged && roles.contains(ROLE_AUDITOR);
  }

  /**
   * Enmascara la porción de host: sustituye lo que sigue al último separador ({@code .} en IPv4,
   * {@code :} en IPv6) por {@code ***}. Sirve para ambas familias.
   */
  static String maskHost(String ip) {
    if (ip == null) {
      return null;
    }
    int idx = Math.max(ip.lastIndexOf('.'), ip.lastIndexOf(':'));
    return idx < 0 ? MASK : ip.substring(0, idx + 1) + MASK;
  }
}
