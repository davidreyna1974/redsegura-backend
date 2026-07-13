# Política de seguridad — redSegura (backend)

## Reporte de vulnerabilidades

Si descubres una vulnerabilidad en los microservicios backend, **no la publiques en un issue
público**. Usa un **canal privado**:

- Preferido: **GitHub Private Vulnerability Reporting** (*Security → Report a vulnerability*),
  una vez publicado el repositorio remoto.
- Alternativo: contacto directo con el mantenedor.

Incluye: descripción, pasos de reproducción, impacto estimado, servicio y commit afectado, y
evidencia (sin exponer datos sensibles de terceros). Objetivo de respuesta: **acuse en 72 h**.

## Prácticas de seguridad del backend

- **RBAC de extremo a extremo:** la autorización se valida en **cada microservicio** (no solo
  en el API Gateway); cada endpoint declara sus roles (RNF-04).
- **Sin secretos en git:** credenciales SSH/SNMP de dispositivos, claves de API y credenciales
  de BD se externalizan por variables de entorno o gestor de secretos (RNF-06).
- **SCA de dependencias:** Dependabot (`maven`, `pip`, `github-actions`) + escaneo en CI,
  bloqueante en severidad `critical` (RNF-08).
- **Errores sin fuga de internos:** respuestas *problem+json* sin stack traces ni nombres de
  tablas/clases (RNF-09); logs estructurados sin PII ni secretos (RNF-17).
- **Alcance autorizado de escaneo (RNF-07):** control técnico obligatorio; durante el proyecto
  apunta exclusivamente a la red simulada.
- **Aplicación de OWASP Top 10 / OWASP API Security Top 10** en cada servicio.

## Alcance

Aplica a los 10 microservicios de este repositorio y a su configuración de build/despliegue.
