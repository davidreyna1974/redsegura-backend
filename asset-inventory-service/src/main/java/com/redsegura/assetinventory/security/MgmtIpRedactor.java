package com.redsegura.assetinventory.security;

import com.redsegura.assetinventory.generated.model.Device;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Redacción de {@code mgmtIp} por rol (RN10/ADR-11). Para el rol Auditor se enmascara el último
 * octeto ({@code 10.0.0.11} → {@code 10.0.0.***}); Administrador y Operador la ven en claro.
 *
 * <p>Es una redacción **server-side**: se aplica sobre el DTO antes de serializar, de modo que el
 * valor real **no viaja** en la respuesta (CYBER-02), no se limita a ocultarlo en el cliente.
 */
@Component
public class MgmtIpRedactor {

  private static final String MASK = "***";
  private static final String ROLE_ADMIN = "ADM";
  private static final String ROLE_OPERATOR = "OPE";
  private static final String ROLE_AUDITOR = "AUD";

  /** Enmascara el {@code mgmtIp} del dispositivo si el usuario actual debe verlo redactado. */
  public void maybeRedact(Device device) {
    if (device != null && shouldRedact()) {
      device.setMgmtIp(mask(device.getMgmtIp()));
    }
  }

  /**
   * Igual que {@link #maybeRedact(Device)} para cada elemento de una lista (evita re-evaluar rol).
   */
  public void maybeRedact(List<Device> devices) {
    if (devices != null && shouldRedact()) {
      devices.forEach(d -> d.setMgmtIp(mask(d.getMgmtIp())));
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

  /** Sustituye el último octeto por {@code ***}; si no hay punto, enmascara todo el valor. */
  static String mask(String ip) {
    if (ip == null) {
      return null;
    }
    int lastDot = ip.lastIndexOf('.');
    return lastDot < 0 ? MASK : ip.substring(0, lastDot + 1) + MASK;
  }
}
