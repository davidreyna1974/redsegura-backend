package com.redsegura.assetinventory.security;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Log de auditoría de seguridad (OWASP A09/ADR-11): registra accesos denegados (403),
 * autenticaciones fallidas (401) y mutaciones con su actor. Escribe solo metadatos (actor, acción,
 * id de recurso) — nunca datos sensibles como {@code mgmtIp}, credenciales o secretos (RNF-17).
 */
@Component
public class SecurityAuditLogger {

  private static final Logger LOG = LoggerFactory.getLogger("SECURITY_AUDIT");

  /** Mutación exitosa (alta/edición/baja): deja traza de quién cambió qué recurso. */
  public void mutation(String action, UUID resourceId) {
    LOG.info(
        "event=mutation action={} resource={} actor={} outcome=success",
        action,
        resourceId,
        currentActor());
  }

  /** Acceso denegado (403): el usuario está autenticado pero sin permiso para la operación. */
  public void accessDenied(String method, String uri) {
    LOG.warn(
        "event=access_denied method={} uri={} actor={} outcome=denied",
        method,
        uri,
        currentActor());
  }

  /** Autenticación fallida o ausente (401): sin identidad válida para un recurso protegido. */
  public void authenticationFailure(String method, String uri, String reason) {
    LOG.warn(
        "event=authentication_failure method={} uri={} reason={} outcome=denied",
        method,
        uri,
        reason);
  }

  private static String currentActor() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || auth instanceof AnonymousAuthenticationToken) {
      return "anonymous";
    }
    return auth.getName();
  }
}
