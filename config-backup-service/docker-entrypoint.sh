#!/bin/sh
# Entrypoint del contenedor. Opcionalmente aplica migraciones (CBS_RUN_MIGRATIONS=1, útil en el
# entorno de desarrollo) y arranca uvicorn con apagado con gracia (RNF-13): ante SIGTERM espera a que
# terminen las peticiones en vuelo hasta timeout-graceful-shutdown antes de cerrar.
set -e

if [ "${CBS_RUN_MIGRATIONS:-0}" = "1" ]; then
    echo "Aplicando migraciones Alembic..."
    alembic upgrade head
fi

exec uvicorn app.main:app \
    --host 0.0.0.0 \
    --port 8000 \
    --timeout-graceful-shutdown 25
