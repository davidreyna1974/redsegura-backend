"""Resiliencia de las llamadas SSH salientes (RNF-10): reintentos con backoff exponencial. Las
llamadas a dispositivos son E/S de red que fallan de forma transitoria; se reintentan antes de darse
por perdidas."""

from __future__ import annotations

import time
from collections.abc import Callable


def with_retries[T](
    fn: Callable[[], T],
    *,
    attempts: int = 3,
    base_delay: float = 0.5,
    retry_on: type[Exception] = Exception,
    sleep: Callable[[float], None] = time.sleep,
) -> T:
    """Ejecuta ``fn`` reintentando ante ``retry_on`` con backoff (``sleep`` inyectable en tests)."""
    last_error: Exception | None = None
    for attempt in range(attempts):
        try:
            return fn()
        except retry_on as error:
            last_error = error
            if attempt < attempts - 1:
                sleep(base_delay * (2**attempt))
    assert last_error is not None
    raise last_error
