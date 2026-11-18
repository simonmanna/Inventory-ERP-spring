"""Ed25519 signatures on detector models and agent releases, checked against the vendor's key before either is used.

Signed text (UTF-8): a model ``version|sha256|url``; a release ``version|image|digest``. A signature is the 64-byte
Ed25519 signature in base64, base64url or hex. ``LP_EDGE_TRUST_KEY`` holds one or more public keys separated by
commas (to rotate keys): PEM, base64 of the 32 raw bytes or of the DER SubjectPublicKeyInfo, or a path to a file
holding one of those. Without a key the agent uses neither models nor releases.
"""

from __future__ import annotations

import base64
import binascii
import os
import re
from collections.abc import Sequence

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

_HEX = re.compile(r"^[0-9a-fA-F]{128}$")
_PEM = re.compile(r"-----BEGIN PUBLIC KEY-----.*?-----END PUBLIC KEY-----", re.S)


def model_message(version: str, sha256: str, url: str) -> bytes:
    return f"{version}|{sha256}|{url}".encode("utf-8")


def release_message(version: str, image: str, digest: str) -> bytes:
    return f"{version}|{image}|{digest}".encode("utf-8")


def decode_signature(text: str) -> bytes | None:
    value = text.strip()
    if _HEX.match(value):
        return bytes.fromhex(value)
    padded = value + "=" * (-len(value) % 4)
    for decoder in (base64.b64decode, base64.urlsafe_b64decode):
        try:
            raw = decoder(padded)
        except (binascii.Error, ValueError):
            continue
        if len(raw) == 64:
            return raw
    return None


def load_public_key(text: str) -> Ed25519PublicKey:
    value = text.strip().replace("\\n", "\n")
    if not value.startswith("-----BEGIN") and os.path.isfile(value):
        with open(value, encoding="ascii") as handle:
            value = handle.read().strip()
    if value.startswith("-----BEGIN"):
        key = serialization.load_pem_public_key(value.encode("ascii"))
    else:
        raw = base64.b64decode(value + "=" * (-len(value) % 4))
        key = Ed25519PublicKey.from_public_bytes(raw) if len(raw) == 32 else serialization.load_der_public_key(raw)
    if not isinstance(key, Ed25519PublicKey):
        raise ValueError("the trust key is not an Ed25519 public key")
    return key


class TrustStore:
    def __init__(self, keys: Sequence[Ed25519PublicKey]) -> None:
        if not keys:
            raise ValueError("no trust key")
        self._keys = list(keys)

    @classmethod
    def parse(cls, value: str | None) -> TrustStore | None:
        if not value or not value.strip():
            return None
        text = value.replace("\\n", "\n")
        pems = _PEM.findall(text)
        rest = _PEM.sub("", text)
        parts = pems + [p for p in rest.split(",") if p.strip()]
        return cls([load_public_key(p) for p in parts])

    def verify(self, message: bytes, signature: str) -> bool:
        raw = decode_signature(signature or "")
        if raw is None:
            return False
        for key in self._keys:
            try:
                key.verify(raw, message)
                return True
            except InvalidSignature:
                continue
        return False
