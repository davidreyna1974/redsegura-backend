# Imagen de config-backup-service (Python/FastAPI).
# Contexto de build = raíz del repo backend (consistente con los demás servicios y con el CI).
# Etapa 1 — construir un venv con las dependencias de runtime.
FROM python:3.12-slim AS build
WORKDIR /build
# git es necesario en runtime (GitPython versiona las configuraciones) y en build para deps.
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
COPY config-backup-service/requirements.txt ./
# Solo dependencias de runtime (las de test/calidad viven tras el marcador "# Test / calidad").
# Sustitución de comandos POSIX (compatible con dash, el sh de la imagen slim).
RUN pip install --no-cache-dir $(sed '/# Test \/ calidad/,$d' requirements.txt | grep -vE '^#|^$')

# Etapa 2 — runtime ligero.
FROM python:3.12-slim
WORKDIR /app
# git: requerido por GitPython en runtime (RF-07). Limpieza de listas apt para reducir la imagen.
RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1
COPY config-backup-service/app/ app/
COPY config-backup-service/alembic/ alembic/
COPY config-backup-service/alembic.ini ./
COPY config-backup-service/pyproject.toml ./
COPY config-backup-service/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh
# Directorio del repo Git interno de configuraciones (RF-07), propiedad del usuario no-root.
RUN mkdir -p /var/lib/config-backup/repo
# Hardening: usuario NO-root (reduce el impacto ante un compromiso; satisface los escáneres).
RUN useradd --system --uid 1001 --home-dir /app --shell /usr/sbin/nologin appuser \
    && chown -R appuser:appuser /app /var/lib/config-backup
USER appuser
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/api/v1/health/liveness').status==200 else 1)"
ENTRYPOINT ["docker-entrypoint.sh"]
