"""Resumable clip upload in 1 MiB chunks (``GET``/``POST /evidence/{id}/upload``).

The backend's ``receivedBytes`` is the truth: every attempt starts there, so a lost answer or a restart costs at most
one chunk. A ``409`` "Expected offset N" means "read the position again". A checksum mismatch fails the upload: the
clip on the box no longer matches its capture hash ("Checksum does not match the clip registered at capture" on any
chunk, "The clip does not match its checksum" after the last). So does "The clip was not requested": the backend no
longer wants this upload (it lapsed, or failed its check).
"""

from __future__ import annotations

import logging
import threading
from collections.abc import Callable
from typing import Any

from lp_edge.api import ApiClient, ApiError

log = logging.getLogger(__name__)

CHUNK = 1 << 20
CHECKSUM_MESSAGE = "checksum does not match"
#: Parts of the backend's 409 messages after which sending again cannot succeed.
TERMINAL_CONFLICTS = (CHECKSUM_MESSAGE, "does not match its checksum", "was not requested")


class UploadFailed(Exception):
    def __init__(self, message: str, code: str | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.code = code


def upload(
    api: ApiClient,
    evidence_id: str,
    *,
    total: int,
    sha256: str,
    read: Callable[[int, int], bytes],
    content_type: str | None = None,
    chunk_size: int = CHUNK,
    max_retries: int = 8,
    stop: threading.Event | None = None,
) -> dict[str, Any]:
    """Upload until the backend says ``complete``; returns its last status."""
    if total <= 0:
        raise UploadFailed("the clip is empty")
    chunk_size = max(1, min(chunk_size, CHUNK))
    stop = stop or threading.Event()
    retries = 0
    conflicts = 0
    status = _status(api, evidence_id, stop, max_retries)
    while True:
        if status.get("complete"):
            return status
        if stop.is_set():
            raise UploadFailed("the agent is stopping")
        offset = int(status.get("receivedBytes") or 0)
        if offset < 0 or offset > total:
            raise UploadFailed(f"the server holds {offset} bytes of a {total}-byte clip")
        length = min(chunk_size, total - offset)
        if length <= 0:
            # every byte is there but the upload is not complete: ask once more, then give up
            status = _status(api, evidence_id, stop, max_retries)
            if status.get("complete"):
                return status
            raise UploadFailed(f"the upload is not complete after the last byte (state {status.get('state')})")
        data = read(offset, length)
        if len(data) != length:
            raise UploadFailed("the clip on the agent is shorter than registered", code="not_found")
        try:
            answer = api.upload_chunk(evidence_id, offset, total, sha256, data, content_type=content_type)
        except ApiError as exc:
            if exc.status == 409:
                if any(part in exc.message.lower() for part in TERMINAL_CONFLICTS):
                    raise UploadFailed(exc.message) from None
                conflicts += 1
                if conflicts > 5:
                    raise UploadFailed(exc.message) from None
                log.info("upload of %s: %s; reading the position again", evidence_id, exc.message)
                status = _status(api, evidence_id, stop, max_retries)
                continue
            if exc.status in (400, 403, 404, 413, 422):
                raise UploadFailed(exc.message) from None
            retries += 1
            if retries > max_retries:
                raise UploadFailed(f"upload failed after {max_retries} retries: {exc.message}") from None
            stop.wait(min(60.0, 2.0**retries))
            status = _status(api, evidence_id, stop, max_retries)
            continue
        retries = 0
        conflicts = 0
        if isinstance(answer, dict) and "receivedBytes" in answer:
            status = answer
        else:
            status = {"receivedBytes": offset + length, "complete": offset + length >= total and bool(answer)}
            if offset + length >= total:
                status = _status(api, evidence_id, stop, max_retries)


def _status(api: ApiClient, evidence_id: str, stop: threading.Event, max_retries: int) -> dict[str, Any]:
    retries = 0
    while True:
        try:
            return api.upload_status(evidence_id)
        except ApiError as exc:
            if not exc.retryable or stop.is_set():
                raise UploadFailed(exc.message) from None
            retries += 1
            if retries > max_retries:
                raise UploadFailed(f"the upload position could not be read: {exc.message}") from None
            stop.wait(min(60.0, 2.0**retries))
