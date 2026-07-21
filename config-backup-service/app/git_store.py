"""Versionado de configuraciones en un repositorio Git interno (RF-07). Cada respaldo exitoso es un
commit; el diff entre versiones se obtiene del propio Git. Un fichero por (dispositivo, tipo):
``<deviceId>/running.cfg`` y ``<deviceId>/startup.cfg``."""

from __future__ import annotations

from pathlib import Path

from git import Repo


class GitStore:
    def __init__(self, repo_path: str) -> None:
        self._path = Path(repo_path)
        self._repo = self._open_or_init()

    def _open_or_init(self) -> Repo:
        if (self._path / ".git").exists():
            return Repo(self._path)
        self._path.mkdir(parents=True, exist_ok=True)
        repo = Repo.init(self._path)
        with repo.config_writer() as writer:
            writer.set_value("user", "name", "config-backup-service")
            writer.set_value("user", "email", "cbs@redsegura.local")
        return repo

    def save_config(self, device_id: str, running: str, startup: str, message: str) -> str:
        """Escribe ambas configuraciones y las committea; devuelve el hash del commit."""
        device_dir = self._path / device_id
        device_dir.mkdir(parents=True, exist_ok=True)
        running_file = device_dir / "running.cfg"
        startup_file = device_dir / "startup.cfg"
        running_file.write_text(running, encoding="utf-8")
        startup_file.write_text(startup, encoding="utf-8")
        self._repo.index.add([str(running_file), str(startup_file)])
        commit = self._repo.index.commit(message)
        return str(commit.hexsha)

    def diff(self, device_id: str, from_ref: str, to_ref: str, config_type: str = "running") -> str:
        """Diferencia unificada de un fichero de config entre dos commits."""
        relative = f"{device_id}/{config_type}.cfg"
        return str(self._repo.git.diff(from_ref, to_ref, "--", relative))
