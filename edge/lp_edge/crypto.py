"""AES-256-GCM for everything the agent keeps on disk: the video buffer, kept clips, drawer references.

The key is per agent: generated on first start in ``<data>/keys/buffer.key`` (mode 0600), or given as
``LP_EDGE_BUFFER_KEY`` (base64 of 32 bytes) from the host's secret store. It protects the files against anyone who
copies them without the key; protecting a stolen box needs full-disk encryption on the host as well.
"""

from __future__ import annotations

import base64
import binascii
import os
from pathlib import Path

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from lp_edge.files import write_private

KEY_BYTES = 32
NONCE_BYTES = 12
TAG_BYTES = 16
OVERHEAD = NONCE_BYTES + TAG_BYTES


class IntegrityError(Exception):
    """Ciphertext that does not decrypt: tampered, truncated, or encrypted with another key."""


class Cipher:
    """Authenticated encryption with a random nonce per message."""

    def __init__(self, key: bytes) -> None:
        if len(key) != KEY_BYTES:
            raise ValueError("the buffer key must be 32 bytes (AES-256)")
        self._aead = AESGCM(key)

    def seal(self, plaintext: bytes, aad: bytes = b"") -> bytes:
        nonce = os.urandom(NONCE_BYTES)
        return nonce + self._aead.encrypt(nonce, plaintext, aad)

    def open(self, blob: bytes, aad: bytes = b"") -> bytes:
        if len(blob) < OVERHEAD:
            raise IntegrityError("ciphertext is truncated")
        try:
            return self._aead.decrypt(blob[:NONCE_BYTES], blob[NONCE_BYTES:], aad)
        except InvalidTag as exc:
            raise IntegrityError("ciphertext does not match its tag") from exc


def decode_key(value: str) -> bytes:
    try:
        key = base64.b64decode(value.strip(), validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ValueError("LP_EDGE_BUFFER_KEY must be base64") from exc
    if len(key) != KEY_BYTES:
        raise ValueError("LP_EDGE_BUFFER_KEY must hold 32 bytes")
    return key


def load_or_create_key(path: Path, env_value: str | None = None) -> bytes:
    """The buffer key from the environment, else from ``path``, else a new random key saved to ``path``."""
    if env_value:
        return decode_key(env_value)
    if path.exists():
        return decode_key(path.read_text(encoding="ascii"))
    key = os.urandom(KEY_BYTES)
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(path.parent, 0o700)
    except OSError:
        pass
    write_private(path, base64.b64encode(key) + b"\n")
    return key
