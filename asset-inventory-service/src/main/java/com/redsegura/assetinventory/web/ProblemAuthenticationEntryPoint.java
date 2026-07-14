package com.redsegura.assetinventory.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redsegura.assetinventory.security.SecurityAuditLogger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Respuesta 401 en {@code application/problem+json} (ADR-08) cuando falta autenticación o el token
 * es inválido, en vez del cuerpo vacío por defecto. Registra el intento en el log de seguridad
 * (ADR-11).
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;
  private final SecurityAuditLogger auditLogger;

  public ProblemAuthenticationEntryPoint(
      ObjectMapper objectMapper, SecurityAuditLogger auditLogger) {
    this.objectMapper = objectMapper;
    this.auditLogger = auditLogger;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    auditLogger.authenticationFailure(
        request.getMethod(), request.getRequestURI(), authException.getClass().getSimpleName());
    ProblemResponseWriter.write(
        response,
        objectMapper,
        HttpStatus.UNAUTHORIZED,
        "No autenticado",
        "Se requiere autenticación válida para acceder a este recurso",
        "UNAUTHENTICATED");
  }
}
