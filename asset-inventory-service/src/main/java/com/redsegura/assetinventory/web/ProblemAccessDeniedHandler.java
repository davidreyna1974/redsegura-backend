package com.redsegura.assetinventory.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redsegura.assetinventory.security.SecurityAuditLogger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Respuesta 403 en {@code application/problem+json} (ADR-08) cuando el usuario está autenticado pero
 * sin permiso para la operación (RBAC). Registra la denegación con su actor (OWASP A09/ADR-11).
 */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

  private final ObjectMapper objectMapper;
  private final SecurityAuditLogger auditLogger;

  public ProblemAccessDeniedHandler(ObjectMapper objectMapper, SecurityAuditLogger auditLogger) {
    this.objectMapper = objectMapper;
    this.auditLogger = auditLogger;
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    auditLogger.accessDenied(request.getMethod(), request.getRequestURI());
    ProblemResponseWriter.write(
        response,
        objectMapper,
        HttpStatus.FORBIDDEN,
        "Acceso denegado",
        "No tiene permisos para realizar esta operación",
        "ACCESS_DENIED");
  }
}
