"""File helpers: private files written atomically, and names that are safe to use as paths."""

from __future__ import annotations

import hashlib
import os
import re
import tempfile
from pathlib import Path

_SAFE = re.compile(r"[^A-Za-z0-9_.-]")


def ensure_private_dir(path: Path) -> Path:
    """Create ``path`` (and parents) readable by the agent's user only."""
    path.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(path, 0o700)
    except OSError:
        pass
    return path


def write_private(path: Path, data: bytes) -> None:
    """Write ``data`` to ``path`` atomically with mode 0600 (no partial file is ever visible)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def safe_name(value: str) -> str:
    """``value`` with anything but letters, digits, ``_``, ``.`` and ``-`` replaced, for use as a file name."""
    cleaned = _SAFE.sub("_", value)
    if not cleaned or cleaned in (".", ".."):
        raise ValueError("empty name")
    return cleaned


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()
