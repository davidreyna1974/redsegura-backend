"""RNF07-01/RES-01/02: control de alcance de conexión (RNF-07) y reintentos con backoff (RNF-10)."""

from __future__ import annotations

import pytest
from app.connectors.base import BackupConnectionError
from app.connectors.resilience import with_retries
from app.connectors.scope import OutOfScopeError, assert_in_scope, is_in_scope


def test_in_scope_only_for_allowed_cidrs() -> None:
    assert is_in_scope("10.1.2.3", "10.0.0.0/8")
    assert not is_in_scope("8.8.8.8", "10.0.0.0/8,192.168.0.0/16")


def test_assert_out_of_scope_raises() -> None:
    with pytest.raises(OutOfScopeError):
        assert_in_scope("8.8.8.8", "10.0.0.0/8")


def test_with_retries_succeeds_after_transient_failures() -> None:
    calls = {"n": 0}

    def flaky() -> str:
        calls["n"] += 1
        if calls["n"] < 3:
            raise BackupConnectionError("transitorio")
        return "ok"

    result = with_retries(flaky, retry_on=BackupConnectionError, sleep=lambda _: None)

    assert result == "ok"
    assert calls["n"] == 3


def test_with_retries_reraises_after_exhausting_attempts() -> None:
    def always_fail() -> str:
        raise BackupConnectionError("caído")

    with pytest.raises(BackupConnectionError):
        with_retries(always_fail, attempts=2, retry_on=BackupConnectionError, sleep=lambda _: None)
